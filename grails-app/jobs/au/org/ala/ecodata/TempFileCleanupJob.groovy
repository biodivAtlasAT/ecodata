package au.org.ala.ecodata

import grails.core.GrailsApplication

class TempFileCleanupJob {

    GrailsApplication grailsApplication

    static triggers = {
        cron name: "midnight", cronExpression: "0 0 0 * * ? *"
    }

    def execute() {
        int daysToKeep = grailsApplication.config.getProperty('temp.file.cleanup.days', Integer, 3)
        File tempDirectory = new File("${grailsApplication.config.getProperty('temp.dir')}")
        if (tempDirectory.exists()) {
            log.info("Removing all files from ${tempDirectory.getAbsolutePath()} that are more than ${daysToKeep} day(s) old...")
            int count = 0
            tempDirectory.listFiles().each { File file ->
                if (file.lastModified() < new Date().minus(daysToKeep).time) {
                    file.isDirectory() ? file.deleteDir() : file.delete()
                    count++
                }
            }

            log.info("Deleted ${count} temp files and/or directories from ${grailsApplication.config.getProperty('temp.dir')}")
        }
    }
}
