package au.org.ala.ecodata.paratoo

import au.org.ala.ecodata.*
import au.org.ala.ecodata.converter.ISODateBindingConverter
import au.org.ala.ecodata.metadata.OutputMetadata
import au.org.ala.ecodata.metadata.PropertyAccessor
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import groovy.util.logging.Slf4j
import org.locationtech.jts.geom.Geometry
/**
 * Configuration about how to work with a Paratoo/Monitor protocol
 */
@Slf4j
@JsonIgnoreProperties(['metaClass', 'errors', 'expandoMetaClass'])
class ParatooProtocolConfig {

    String name
    String apiEndpoint
    boolean usesPlotLayout = true
    List tags
    String geometryType = 'Polygon'

    String geometryPath
    String startDatePath = 'start_date_time'
    String endDatePath = 'end_date_time'
    String surveyIdPath = 'survey_metadata'
    String plotVisitPath = 'plot_visit'
    String plotProtocolObservationDatePath = "date_time"
    String plotVisitStartDatePath = "${plotVisitPath}.start_date"
    String plotVisitEndDatePath = "${plotVisitPath}.end_date"
    String plotLayoutPath = "${plotVisitPath}.plot_layout"
    String plotLayoutIdPath = "${plotLayoutPath}.id"
    String plotLayoutPointsPath = "${plotLayoutPath}.plot_points"
    String plotSelectionPath = "${plotLayoutPath}.plot_selection"
    String plotLayoutDimensionLabelPath = "${plotLayoutPath}.plot_dimensions.label"
    String plotLayoutTypeLabelPath = "${plotLayoutPath}.plot_type.label"

    String getApiEndpoint(ParatooCollectionId surveyId) {
        apiEndpoint ?: defaultEndpoint(surveyId)
    }
    Map overrides = [dataModel: [:], viewModel: [:]]

    ParatooCollectionId surveyId
    TimeZone clientTimeZone

    private static String removeMilliseconds(String isoDateWithMillis) {
        if (!isoDateWithMillis) {
            return isoDateWithMillis
        }
        DateUtil.format(DateUtil.parseWithMilliseconds(isoDateWithMillis))
    }

    String getStartDate(Map surveyData) {
        if(startDatePath == null || surveyData == null) {
            return null
        }

        def date
        if (usesPlotLayout) {
            List dates = getDatesFromObservation(surveyData)
            date = dates ? DateUtil.format(dates.first()) : null
            return date
        }
        else {
            date = getProperty(surveyData, startDatePath)
            if (!date) {
                date = getPropertyFromSurvey(surveyData, startDatePath)
            }

            date = getFirst(date)
            return removeMilliseconds(date)
        }
    }

    /**
     * Get date from plotProtocolObservationDatePath and sort them.
     * @param surveyData - reverse lookup output which includes survey and observation data
     * @return
     */
    List getDatesFromObservation(Map surveyData) {
        Map surveysData = surveyData.findAll { key, value ->
            ![ getSurveyAttributeName(), ParatooService.PARATOO_DATAMODEL_PLOT_SELECTION,
              ParatooService.PARATOO_DATAMODEL_PLOT_VISIT, ParatooService.PARATOO_DATAMODEL_PLOT_LAYOUT].contains(key)
        }
        List result = []
        ISODateBindingConverter converter = new ISODateBindingConverter()
        surveysData.each { key, value ->
            def dates = getProperty(value, plotProtocolObservationDatePath)
            dates = dates instanceof List ? dates : [dates]

            result.addAll(dates.collect { String date ->
                date ? converter.convert(date, ISODateBindingConverter.FORMAT) : null
            })
        }

        result = result.findAll { it != null }
        result.sort()
    }

    def getPropertyFromSurvey(Map surveyData, String path) {
        surveyData = getSurveyData(surveyData)
        getProperty(surveyData, path)
    }

    String getEndDate(Map surveyData) {
        if(endDatePath == null || surveyData == null) {
            return null
        }

        def date
        if (usesPlotLayout) {
            def dates = getDatesFromObservation(surveyData)
            date = dates ? DateUtil.format(dates.last()) : null
            return date
        }
        else {
            date = getProperty(surveyData, endDatePath)
            if (!date) {
                date = getPropertyFromSurvey(surveyData, endDatePath)
            }

            date = getFirst(date)
            return removeMilliseconds(date)
        }
    }

    Map getSurveyId(Map surveyData) {
        if(surveyIdPath == null || surveyData == null) {
            return null
        }

        def result = getProperty(surveyData, surveyIdPath)
        if (result == null) {
            result = getPropertyFromSurvey(surveyData, surveyIdPath)
        }

        result = getFirst(result)
        result
    }

    private Map extractSiteDataFromPath(Map survey) {
        Map surveyData = getSurveyData(survey)
        def geometryData = getProperty(surveyData, geometryPath)
        geometryData = getFirst(geometryData)
        extractGeometryFromSiteData(geometryData)
    }

    String getSurveyAttributeName() {
        String surveyAttribute = apiEndpoint
        if(surveyAttribute?.endsWith('s')) {
            surveyAttribute = surveyAttribute.substring(0, surveyAttribute.length() - 1)
        }

        surveyAttribute
    }

    Map getSurveyDataFromObservation (Map observation) {
        String surveyAttribute = getSurveyAttributeName()

        def survey = observation[surveyAttribute]
        if (survey instanceof List) {
            return survey[0]
        }

        survey
    }

    private List extractFeatures (Map observation, ActivityForm form) {
        List features = []
        form.sections.each { FormSection section ->
            OutputMetadata om = new OutputMetadata(section.template)
            Map paths = om.getNamesForDataType("feature", null )
            features.addAll(getFeaturesFromPath(observation, paths))
        }

        features
    }

    private List getFeaturesFromPath (Map output, Map paths) {
        List features = []
        paths.each { String name, node ->
            if (node instanceof Boolean) {
                features.add(output[name])
                // todo later: add featureIds and modelId for compliance with feature behaviour of reports
            }

            // recursive check for feature
            if (node instanceof Map) {
                if (output[name] instanceof Map) {
                    features.addAll(getFeaturesFromPath(output[name], node))
                }

                if (output[name] instanceof List) {
                    output[name].eachWithIndex { row, index ->
                        features.addAll(getFeaturesFromPath(row, node))
                    }
                }
            }
        }

        features
    }

    private Map extractGeometryFromSiteData(geometryData) {
        Map geometry = null
        if (geometryData) {
            switch (geometryType) {
                case 'Point':
                    geometry = [type: 'Point', coordinates: [geometryData.lng, geometryData.lat]]
                    break
            }
        } else {
            log.warn("Unable to get spatial data from survey: " + apiEndpoint + ", " + geometryPath)
        }

        geometry
    }

    private static String defaultEndpoint(ParatooCollectionId surveyId) {
        String apiEndpoint = surveyId.survey_metadata?.survey_details?.survey_model
        if (!apiEndpoint.endsWith('s')) {
            apiEndpoint += 's'
        } // strapi makes the endpoint plural sometimes?
        apiEndpoint
    }

    def getProperty(def surveyData, String path) {
        if (!path) {
            return null
        }

        def result = new PropertyAccessor(path).get(surveyData)
        if ((result == null) && (surveyId != null)) {
            path = surveyId.survey_metadata.survey_details.survey_model+'.'+path
            result = new PropertyAccessor(path).get(surveyData)
        }

        result
    }

    Map getGeoJson(Map output, ActivityForm form = null) {
        if (!output) {
            return null
        }

        Map geoJson = null
        if (usesPlotLayout) {
            geoJson = extractSiteDataFromPlotVisit(output)
            // get list of all features associated with observation
            if (geoJson && form && output) {
                geoJson.features = extractFeatures(output, form)
            }
        }
        else if (geometryPath) {
            geoJson = extractSiteDataFromPath(output)
        }
        else if (form && output) {
            List features = extractFeatures(output, form)
            if (features) {
                List featureGeometries = features.collect { it.geometry }
                Geometry geometry = GeometryUtils.getFeatureCollectionConvexHull(featureGeometries)
                String startDateInString = getStartDate(output)
                startDateInString = DateUtil.convertUTCDateToStringInTimeZone(startDateInString, clientTimeZone?:TimeZone.default)
                String name = "${form.name} site - ${startDateInString}"
                geoJson = [
                    type: 'Feature',
                    geometry: GeometryUtils.geometryToGeoJsonMap(geometry),
                    properties: [
                            name: name,
                            description: "${name} (convex hull of all features)",
                    ],
                    features: features
                ]
            }
        }

        geoJson
    }

    Map getPlotVisit (Map surveyData) {
        def result = getProperty(surveyData, plotVisitPath)
        Map plotVisit = getFirst(result)
        copyWithExcludedProperty(plotVisit, ParatooService.PARATOO_DATAMODEL_PLOT_LAYOUT)
    }

    Map getPlotLayout (Map surveyData) {
        def result = getProperty(surveyData, plotLayoutPath)
        Map plotLayout = getFirst(result)
        copyWithExcludedProperty(plotLayout, ParatooService.PARATOO_DATAMODEL_PLOT_SELECTION)
    }

    Map getPlotSelection (Map surveyData) {
        def result = getProperty(surveyData, plotSelectionPath)
        Map plotSelection = getFirst(result)
        copyWithExcludedProperty(plotSelection)
    }

    private Map copyWithExcludedProperty(Map map, String property = null) {
        if (!map) {
            return [:]
        }
        List keys = map.keySet().toList()
        if (property) {
            keys = keys.minus(property)
        }
        Map result = [:]
        keys.each { key ->
            result[key] = map[key]
        }

        result
    }


    boolean matches(Map surveyData, ParatooCollectionId collectionId) {
        Map tmpSurveyId = getSurveyId(surveyData)
        if (!tmpSurveyId) {
            log.error("Cannot find surveyId:")
            log.debug(surveyData.toString())
            return false
        }

        surveyEqualityTest(tmpSurveyId, collectionId)
    }

    static boolean surveyEqualityTest(Map tmpSurveyId, ParatooCollectionId collectionId) {
        tmpSurveyId?.survey_details?.survey_model == collectionId.survey_metadata?.survey_details.survey_model &&
                tmpSurveyId?.survey_details?.time == collectionId.survey_metadata?.survey_details.time &&
                tmpSurveyId?.survey_details?.uuid == collectionId.survey_metadata?.survey_details.uuid
    }

    static def getFirst (def value) {
        if (value instanceof List) {
            try {
                value = value.first()
            }
            catch (NoSuchElementException e) {
                log.warn("List is empty", e)
                value = null
            }
        }

        value
    }

    def getSurveyData (Map survey) {
        if (surveyId) {
            def path = surveyId.survey_metadata.survey_details.survey_model
            getFirst(survey[path])
        }
    }

    private Map extractSiteDataFromPlotVisit(Map survey) {
        Map surveyData = getSurveyData(survey)
        def plotLayoutId = getProperty(surveyData, plotLayoutIdPath) // Currently an int, may become uuid?

        if (!plotLayoutId) {
            log.warn("No plot_layout found in survey at path ${plotLayoutIdPath}")
            return null
        }
        List plotLayoutPoints = getProperty(surveyData, plotLayoutPointsPath)
        Map plotSelection = getProperty(surveyData, plotSelectionPath)
        Map plotSelectionGeoJson = plotSelectionToGeoJson(plotSelection)

        String plotLayoutDimensionLabel = getProperty(surveyData, plotLayoutDimensionLabelPath)
        String plotLayoutTypeLabel = getProperty(surveyData, plotLayoutTypeLabelPath)

        String name = plotSelectionGeoJson.properties.name + ' - ' + plotLayoutTypeLabel + ' (' + plotLayoutDimensionLabel + ')'

        Map plotGeoJson = createFeatureFromGeoJSON(plotLayoutPoints, name, plotLayoutId, plotSelectionGeoJson?.properties?.notes)

        //Map faunaPlotGeoJson = toGeometry(plotLayout.fauna_plot_point)

        // TODO maybe turn this into a feature with properties to distinguish the fauna plot?
        // Or a multi-polygon?

        plotGeoJson
    }

    static Map createFeatureFromGeoJSON(List plotLayoutPoints, String name, def plotLayoutId, String notes  = "") {
        Map plotGeometry = toGeometry(plotLayoutPoints)
        createFeatureObject(plotGeometry, name, plotLayoutId, notes)
    }

    static Map createFeatureObject(Map plotGeometry, String name, plotLayoutId, String notes = "") {
        [
                type      : 'Feature',
                geometry  : plotGeometry,
                properties: [
                        name       : name,
                        externalId : plotLayoutId,
                        description: name,
                        notes      : notes
                ]
        ]
    }

    static Map toGeometry(List points) {
        List coords = points?.findAll { !exclude(it) }.collect {
            [it.lng, it.lat]
        }
        Map plotGeometry = coords ? [
                type       : 'Polygon',
                coordinates: [closePolygonIfRequired(coords)]
        ] : null

        plotGeometry
    }

    static Map createLineStringFeatureFromGeoJSON (List plotLayoutPoints, String name, def plotLayoutId, String notes  = "") {
        Map plotGeometry = toLineStringGeometry(plotLayoutPoints)
        createFeatureObject(plotGeometry, name, plotLayoutId, notes)
    }

    static Map toLineStringGeometry(List points) {
        List coords = points?.collect {
            [it.lng, it.lat]
        }
        Map plotGeometry = coords ? [
                type       : 'LineString',
                coordinates: coords
        ] : null

        plotGeometry
    }

    private static List closePolygonIfRequired(List points) {
        if (points[0][0] != points[-1][0] || points[0][1] != points[-1][1]) {
            points << points[0]
        }
        points
    }

    private static boolean exclude(Map point) {
        point.name?.data?.attributes?.symbol == "C" || point.name?.symbol == "C"// The plot layout has a centre point that we don't want
    }

    // Accepts a Map or ParatooPlotSelectionData as this is used by two separate calls.
    static Map plotSelectionToGeoJson(def plotSelectionData) {
        Map geoJson = [:]
        geoJson.type = 'Feature'
        geoJson.geometry = [
                type:'Point',
                coordinates: [plotSelectionData.recommended_location.lng, plotSelectionData.recommended_location.lat]
        ]
        geoJson.properties = [
                name : plotSelectionData.plot_label,
                externalId: plotSelectionData.uuid,
                description: plotSelectionData.plot_label,
                notes: plotSelectionData.comments
        ]
        geoJson
    }
}
