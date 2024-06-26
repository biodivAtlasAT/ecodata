package au.org.ala.ecodata.converter

import au.org.ala.ecodata.Activity
import au.org.ala.ecodata.Output
import au.org.ala.ecodata.Project
import au.org.ala.ecodata.ProjectActivity
import au.org.ala.ecodata.Organisation
import au.org.ala.ecodata.Site
import groovy.util.logging.Slf4j
import org.apache.commons.lang.StringUtils

/**
 * Utility for converting the data submitted for an Output into one or more Records with the correct Darwin Core fields
 * so that it can be exported to the Biocache.
 * <p/>
 * In order for an Output to be converted successfully into one or more Records, the following rules MUST be met:
 * <ol>
 *     <li>The Output metadata (i.e. the dataModel.json file) must have '"record": true' at the top level
 *     <li>Each dataModel item must have a dataType defined (this includes columns within a list model)
 *     <li>Each dataModel that needs to be mapped to a Darwin Core field must have '"dwcAttribute": "[attribute name]"' defined
 * </ol>
 *
 * The following is a simple example of how an Output metadata model should look in order for Record creation to work:
 * <pre>
 *{*   "modelName": "Sample",
 *   "record": "true",
 *   "dataModel": [
 *{*       "dataType": "text",
 *       "name": "fieldName1",
 *       "dwcAttribute": "darwinCoreTerm1",
 *},
 *{*       "dataType": "list",
 *       "name": "mylist",
 *       "columns": [
 *{*           "name": "col1",
 *           "dataType": "text",
 *           "dwcAttribute": "darwinCoreTerm2"
 *},
 *         ...
 *}*   ]
 *   ...
 *}* </pre>
 */
@Slf4j
class RecordConverter {

    static final List MULTI_ITEM_DATA_TYPES = ["list", "masterDetail"]
    static final String DELIMITER = ";"
    static final String DEFAULT_BASIS_OF_RECORD = "HumanObservation"
    static final String MACHINE_OBSERVATION_BASIS_OF_RECORD = "MachineObservation"
    static final String DEFAULT_LICENCE_TYPE = "https://creativecommons.org/licenses/by/4.0/"
    static final String SYSTEMATIC_SURVEY = "systematic"
    static final List MACHINE_SURVEY_TYPES = ["Bat survey - Echolocation recorder","Fauna survey - Call playback","Fauna survey - Camera trapping"]

    static List<Map> convertRecords(Project project, Organisation organisation, Site site, ProjectActivity projectActivity, Activity activity, Output output, Map data, Map outputMetadata) {
        // Outputs are made up of multiple 'dataModels', where each dataModel could map to one or more Record fields
        // and/or one or more Records. For example, a dataModel with a type of 'list' will map to one Record per item in
        // the list. Further, each item in the list is a 'dataModel' on it's own, which will map to one or more fields.

        // First create the skeleton of the Record. This contains the various object identifiers for related data
        Map baseRecord = [
                outputId         : output.outputId,
                projectId        : activity.projectId,
                projectActivityId: activity.projectActivityId,
                activityId       : activity.activityId,
                userId           : activity.userId
        ]

        // Populate the skeleton with Record attributes which are derived from the Activity. These attributes are shared
        // by all Records that are generated from this Output.
        baseRecord << extractHeaderDetails(project, organisation, site, projectActivity, activity)

        // Split the dataModels in the output into those which produce Record FIELDS, and those which produce multiple
        // Records. When there is a mix of both, the fields generated by the 'singleItemModels' will be shared by all
        // Records that are generated from this Output.
        List singleItemModels
        List multiItemModels
        (singleItemModels, multiItemModels) = outputMetadata?.dataModel?.split {
            //check if dataType is null
            !MULTI_ITEM_DATA_TYPES.contains(it.dataType?.toLowerCase())
        }


        // Split data in fields that constitute the base record and the different species field
        // Each species field will end up generating a record entry.
        // All records will share the same base record data
        List baseRecordModels
        List speciesModels
        (baseRecordModels, speciesModels) = singleItemModels?.split {
            it.dataType?.toLowerCase() != "species"
        }


        // For each singleItemModel, get the appropriate field converter for the data type, generate the individual
        // Record fields and add them to the skeleton Record
        baseRecordModels?.each { Map dataModel ->
            RecordFieldConverter converter = getFieldConverter(dataModel.dataType)
            List<Map> recordFieldSets = converter.convert(data, dataModel)
            baseRecord << recordFieldSets[0]
        }

        List<Map> records = []
        // For each species dataType, where present we will generate a new record
        speciesModels?.each { Map dataModel ->
            RecordFieldConverter converter = getFieldConverter(dataModel.dataType)
            List<Map> recordFieldSets = converter.convert(data, dataModel)
            Map speciesRecord = overrideFieldValues(baseRecord, recordFieldSets[0])

            // We want to create a record in the DB only if species guid is present i.e. species is valid
            if(speciesRecord.guid && speciesRecord.guid != "") {
                records << speciesRecord
            } else {
                log.warn("Record [${speciesRecord}] does not contain full species information. " +
                        "This is most likely a bug.")
            }
        }

        if (multiItemModels) {
            // For each multiItemModel, get the appropriate field converter for the data type and generate the list of field
            // sets which will be converted into Records. For each field set, add a copy of the skeleton Record so it has
            // all the common fields
            multiItemModels?.each { Map dataModel ->
                RecordFieldConverter converter = getFieldConverter(dataModel.dataType)
                List<Map> recordFieldSets = converter.convert(data, dataModel)

                recordFieldSets.each {
                    Map rowRecord = overrideFieldValues(baseRecord, it)
                    if(rowRecord.guid && rowRecord.guid != "") {
                        records << rowRecord
                    } else {
                        log.warn("Multi item Record [${rowRecord}] does not contain species information, " +
                                "was the form intended to work like that?")
                    }
                }
            }
        }

        // We are now left with a list of one or more Maps, where each Map contains all the fields for an individual Record.
        records
    }

    /**
     * Return a new Map with the union of source and additional giving precedence to values from additional
     *
     *
     * If the same key already exists in target it will be overriden
     * @param source the original entries
     * @param additional Entries with precedence
     * @return
     */
    public static Map overrideFieldValues(Map source, Map additional) {

        Map result = [:]
        result << (source ?: [:])
        result << (additional ?: [:])

        result
    }


    protected static RecordFieldConverter getFieldConverter(String outputDataType) {
        String packageName = RecordFieldConverter.class.package.getName()
        String className = "${StringUtils.capitalize(outputDataType).replaceAll("[ _\\-]", "")}Converter"

        RecordFieldConverter converter
        try {
            converter = Class.forName("${packageName}.${className}")?.newInstance()
        } catch (ClassNotFoundException e) {
            log.debug "No specific converter found for output data type ${outputDataType} with class name ${packageName}.${className}, using generic converter"
            converter = new GenericFieldConverter()
        }

        converter
    }

    private
    static Map extractHeaderDetails(Project project, Organisation organisation, Site site, ProjectActivity projectActivity, Activity activity) {
        Map dwcFields = [:]

        // Project fields
        if (project) {
            dwcFields.rightsHolder = project.organisationName
            // check if valid collectory institution id exists

            if(organisation && organisation.collectoryInstitutionId && organisation.collectoryInstitutionId != "null" && organisation.collectoryInstitutionId != '') {
                dwcFields.institutionID = organisation.collectoryInstitutionId
                dwcFields.institutionCode = organisation.name
            }
            else {
                dwcFields.institutionCode = dwcFields.institutionID = project.organisationName
            }
        }

        // ProjectActivity fields
        if (projectActivity) {
            dwcFields.datasetID = projectActivity.projectActivityId
            dwcFields.datasetName = projectActivity.name
            // use licence if specified, otherwise default to CC-0
            if (projectActivity.dataSharingLicense) {
              dwcFields.licence = projectActivity.dataSharingLicense
            }
            else {
              dwcFields.licence = DEFAULT_LICENCE_TYPE
            }

            // human observation is most common record type
            if (projectActivity.methodType == SYSTEMATIC_SURVEY && MACHINE_SURVEY_TYPES.contains(projectActivity.methodName)) {
              dwcFields.basisOfRecord = MACHINE_OBSERVATION_BASIS_OF_RECORD
            }
            else {
              dwcFields.basisOfRecord = DEFAULT_BASIS_OF_RECORD
            }

        }

        // Site fields
        if (site) {
            dwcFields.locationID = site.siteId
            dwcFields.locationName = site.name
            dwcFields.locationRemarks = "${site.description}${site.description && site.notes ? ';' : ''}${site.notes}".toString()
            try {
                if (site.extent?.geometry) {
                    dwcFields.locality = site.extent.geometry.locality
                    if(site.extent.geometry.centre?.size() > 1) {
                        dwcFields.decimalLatitude = site.extent.geometry.centre[1]
                        dwcFields.decimalLongitude = site.extent.geometry.centre[0]
                    }
                }
            } catch (MissingPropertyException e) {
                // Do nothing if the site does not have an extent property.
                // Have to catch MissingPropertyException since there appears to be no other way to determine if a class
                // has a dynamic property.
            }
        }

        // Activity fields
        if (activity) {
            dwcFields.userId = activity.userId
            dwcFields.recordedBy = activity.userId
            dwcFields.eventID = activity.activityId
            dwcFields.eventDate = activity.dateCreated
            dwcFields.verbatimEventDate = activity.startDate
        }

        dwcFields
    }
}
