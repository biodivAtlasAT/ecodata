package au.org.ala.ecodata

import grails.util.Holders
import groovy.util.logging.Slf4j
import org.apache.http.HttpStatus
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

import java.text.SimpleDateFormat
import java.time.Period
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * This job runs once per day and checks for:
 * 1) Users who haven't logged into a hub for a configurable amount of time
 * 2) Users who will soon be subject to removal of access because of condition (1)
 * 3) Specific roles that have passed the expiry date
 */
@Slf4j
class AccessExpiryJob {

    /** Used to lookup the email template warning a user that their access will soon expire */
    static final String WARNING_EMAIL_KEY = 'accessexpiry.warning.email'

    /** Used to lookup the email template informing a user that their access has expired */
    static final String ACCESS_EXPIRED_EMAIL_KEY = 'accessexpiry.expired.email'

    /** Used ot lookup the email template informing a user that their elevated permission has expired */
    static final String PERMISSION_EXPIRED_EMAIL_KEY = 'permissionexpiry.expired.email'

    /** Used ot lookup the email template informing a user that their elevated permission will expire 1 month from now */
    static final String PERMISSION_WARNING_EMAIL_KEY = 'permissionwarning.expiry.email'

    /** Used for situations where a large number of users are to be expired at once to avoid getting flagged as a bad actor by the email server */
    static final int DEFAULT_MAX_EMAILS_TO_SEND = 1000

    private static final int BATCH_SIZE = 100

    PermissionService permissionService
    UserService userService
    HubService hubService
    EmailService emailService

    static triggers = {
        String accessExpiryCron = Holders.config.getProperty("access.expiry.cron.expression", String, "0 10 3 * * ? *")
        // Allow the reporting server to override the default to prevent this job from running
        // on both the reporting and primary server
        if (accessExpiryCron) {
            cron name: "accessExpiry", cronExpression: accessExpiryCron
        }
    }

    /**
     * Called when the cron job is fired - checks for users and UserPermissions that need to be removed due
     * to inactivity or reaching their expiry date.
     */
    void execute() {

        int maxEmailsToSend = Holders.config.getProperty("access.expiry.maxEmails", Integer, DEFAULT_MAX_EMAILS_TO_SEND)
        ZonedDateTime processingTime = ZonedDateTime.now(ZoneOffset.UTC)
        int emailsSent = 0
        User.withNewSession {
            emailsSent += processInactiveUsers(processingTime, maxEmailsToSend)
        }

        if (maxEmailsToSend - emailsSent > 0) {
            UserPermission.withNewSession {
                emailsSent += processExpiredPermissions(processingTime, maxEmailsToSend-emailsSent)
            }
        }
        else {
            log.info("AccessExpiryJob stopped processing due to email limit")
        }

        if (maxEmailsToSend - emailsSent > 0) {
            UserPermission.withNewSession {
                emailsSent += processWarningPermissions(processingTime, maxEmailsToSend-emailsSent)
            }
        }
        else {
            log.info("AccessExpiryJob stopped processing due to email limit")
        }

        log.info("AccessExpiryJob sent "+emailsSent+ " emails")
    }

    /**
     * Finds users who have not logged in for a Hub configurable amount of time, and either warns them
     * their access is due to expire, or expires their access to the Hub.
     * @param processingTime The time this job started running
     * @param maxEmailsToSend The maximum number of emails allowed to be sent by this method
     * @return the number of emails sent
     */
    int processInactiveUsers(ZonedDateTime processingTime, int maxEmailsToSend) {
        log.info("AccessExpiryJob is searching for inactive users for processing")
        List<Hub> hubs = hubService.findHubsEligibleForAccessExpiry()
        Date processingTimeAsDate = Date.from(processingTime.toInstant())
        int emailsSent = 0
        for (Hub hub : hubs) {
            // Get the configuration for the job from the hub
            Period period = hub.accessManagementOptions.getAccessExpiryPeriod()
            if (period) {
                Date loginDateEligibleForAccessRemoval = Date.from(processingTime.minus(period).toInstant())
                emailsSent += processExpiredUserAccess(hub, loginDateEligibleForAccessRemoval, processingTimeAsDate, maxEmailsToSend - emailsSent)

                period = hub.accessManagementOptions.getAccessExpiryWarningPeriod()
                if (period && emailsSent < maxEmailsToSend) {
                    Date loginDateEligibleForWarning = Date.from(processingTime.minus(period).toInstant())

                    emailsSent += processInactiveUserWarnings(
                            hub, loginDateEligibleForAccessRemoval, loginDateEligibleForWarning, processingTimeAsDate, maxEmailsToSend - emailsSent)
                }
            }
        }
        emailsSent
    }

    private int processExpiredUserAccess(Hub hub, Date loginDateEligibleForAccessRemoval, Date processingTime, int maxEmailsToSend) {
        int offset = 0
        int max = BATCH_SIZE
        int emailsSent = 0
        List<User> users = userService.findUsersNotLoggedInToHubSince(hub.hubId, loginDateEligibleForAccessRemoval, offset, max)
        while (users && emailsSent < maxEmailsToSend) {
            for (User user : users) {
                UserHub userHub = user.getUserHub(hub.hubId)
                if (!userHub.accessExpired()) {
                    log.info("Deleting all permissions for user ${user.userId} in hub ${hub.urlPath}")
                    Map result = permissionService.deleteUserPermissionByUserId(user.userId, hub.hubId)
                    userHub.accessExpiredDate = processingTime
                    user.save()
                    if (result.status == HttpStatus.SC_OK) {
                        sendEmail(hub, user.userId, ACCESS_EXPIRED_EMAIL_KEY)
                        emailsSent++
                    }
                }
                if (emailsSent >= maxEmailsToSend) {
                    break
                }
            }
            offset += BATCH_SIZE
            users = userService.findUsersNotLoggedInToHubSince(hub.hubId, loginDateEligibleForAccessRemoval, offset, max)
        }
        emailsSent
    }

    private int processInactiveUserWarnings(
            Hub hub, Date loginDateEligibleForWarning, Date loginDateEligibleForAccessRemoval, Date processingTime, int maxEmailsToSend) {
        int emailsSent = 0
        int max = BATCH_SIZE
        int offset = 0
        List<User> users = userService.findUsersWhoLastLoggedInToHubBetween(
                hub.hubId, loginDateEligibleForWarning, loginDateEligibleForAccessRemoval, offset, max)
        while (users && emailsSent < maxEmailsToSend) {
            for (User user : users) {

                UserHub userHub = user.getUserHub(hub.hubId)
                // Filter out users who have already been sent a warning
                if (!userHub.sentAccessRemovalDueToInactivityWarning()) {

                    log.info("Sending inactivity warning to user ${user.userId} in hub ${hub.urlPath}")
                    sendEmail(hub, user.userId, WARNING_EMAIL_KEY)
                    emailsSent++
                    userHub.inactiveAccessWarningSentDate = processingTime
                    user.save()
                }
                if (emailsSent >= maxEmailsToSend) {
                    break
                }
            }
            offset += BATCH_SIZE
            users = userService.findUsersWhoLastLoggedInToHubBetween(hub.hubId, loginDateEligibleForWarning, loginDateEligibleForAccessRemoval, offset, max)
        }
        emailsSent
    }

    private void sendEmail(Hub hub, String userId, String key) {
        def userDetails = userService.lookupUserDetails(userId)
        emailService.sendTemplatedEmail(
                hub.urlPath,
                key+'.subject',
                key+'.body',
                [:],
                [userDetails.email],
                [],
                hub.emailReplyToAddress,
                hub.emailFromAddress)
        log.warn("Sending email for "+userId+", key: "+key)
    }

    /**
     * Finds all UserPermissions with an expiry date that is before the supplied processing time and removes them.
     * @param processingTime the time this job started running.
     * @param maxEmailsToSend The maximum number of emails allowed to be sent by this method
     * @return the number of emails sent
     */
    int processExpiredPermissions(ZonedDateTime processingTime, int maxEmailsToSend) {

        int emailsSent = 0
        Date processingDate = Date.from(processingTime.toInstant())
        List permissions = permissionService.findPermissionsByExpiryDate(processingDate)

        for (UserPermission permission : permissions) {

            log.info("Deleting expired permission for user ${permission.userId} for entity ${permission.entityType} with id ${permission.entityId}")
            permission.delete()

            // Find the hub attached to the expired permission.
            String hubId = permissionService.findOwningHubId(permission)
            Hub hub = Hub.findByHubId(hubId)

            sendEmail(hub, permission.userId, PERMISSION_EXPIRED_EMAIL_KEY)
            emailsSent++
            if (emailsSent >= maxEmailsToSend) {
                break
            }
        }
        emailsSent
    }
    /**
     * Finds all UserPermissions with an expiry date that is before the supplied processing time and removes them.
     * @param processingTime the time this job started running.
     * @param maxEmailsToSend The maximum number of emails allowed to be sent by this method
     * @return the number of emails sent
     */
    int processWarningPermissions(ZonedDateTime processingTime, int maxEmailsToSend) {
        log.info("AccessExpiryJob process is searching for users expiring 1 month from today")

        Date processingTimeAsDate = Date.from(processingTime.toInstant())
        Date monthFromNow = Date.from(processingTime.plusMonths(1).toInstant())
        List permissions = permissionService.findAllByExpiryDate(processingTimeAsDate, monthFromNow)
        int emailsSent = 0

        for (UserPermission it : permissions) {
            User user = userService.findByUserId(it.userId)
            if (user) {
                // Find the hub attached to the expired permission.
                String hubId = permissionService.findOwningHubId(it)
                UserHub userHub = user.getUserHub(hubId)
                if (!userHub.permissionWarningSentDate) {

                    Hub hub = Hub.findByHubId(hubId)
                    log.info("Sending expiring role warning to user ${it.userId} in hub ${hub.urlPath}")

                    sendEmail(hub, it.userId, PERMISSION_WARNING_EMAIL_KEY)
                    emailsSent++
                    userHub.permissionWarningSentDate = processingTimeAsDate
                    user.markDirty('userHubs') // GORM seems to have trouble detecting the embedded object is dirty sometimes.
                    user.save()
                }
                if (emailsSent >= maxEmailsToSend) {
                    break
                }
            }
        }
        emailsSent
    }
}
