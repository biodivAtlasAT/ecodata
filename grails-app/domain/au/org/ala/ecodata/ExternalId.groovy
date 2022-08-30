package au.org.ala.ecodata

import groovy.transform.EqualsAndHashCode

/**
 * Associates an id held in an external system with a Project
 */
@EqualsAndHashCode
class ExternalId implements Comparable {

    enum IdType { INTERNAL_ORDER_NUMBER, TECH_ONE_CODE, WORK_ORDER, GRANT_AWARD, GRANT_OPPORTUNITY }

    static constraints = {
    }

    IdType idType
    String externalId

    @Override
    int compareTo(Object otherId) {
        ExternalId other = (ExternalId)otherId
        return (idType.ordinal()+externalId).compareTo(other?.idType?.ordinal()+other?.externalId)
    }
}