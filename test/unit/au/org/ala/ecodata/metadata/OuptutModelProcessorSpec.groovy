package au.org.ala.ecodata.metadata

import grails.converters.JSON
import grails.test.GrailsUnitTestCase
import spock.lang.Specification

/**
 * Tests the OutputModelProcessor
 */
class OuptutModelProcessorSpec extends Specification {

    private OutputModelProcessor outputModelProcessor = new OutputModelProcessor()

    /** The regevetation details output has a single table */
    void "test the revegetation details output"() {
        when:
            def regevetationDetailsMetadata = getJsonResource('revegetationDetailsMetadata')
            def flat =  outputModelProcessor.flatten(
                getJsonResource('sampleRevegetationDetails1'), new OutputMetadata(regevetationDetailsMetadata))
            def expectedNumberPlanted = [1,2]


        then:
            flat.size() == 2

            flat.eachWithIndex { flatOutput, i ->
                flatOutput.outputId == 'output1'
                flatOutput.activityId == 'activity1'
                flatOutput.numberPlanted == expectedNumberPlanted[i]
            }
    }

    void "Data models with a single nested list can be flattened"() {
        setup:
        def modelMetadata = new OutputMetadata(getJsonResource('singleNestedDataModel'))
        def modelData = getJsonResource('singleSampleNestedDataModel')

        when:
        List flat = outputModelProcessor.flatten2(modelData, modelMetadata)

        then:
        flat.size() == 2

        flat[0] == [name:"Nested lists", number1:"3", notes:"notes", "list.value1":"0.value1"]
        flat[1] == [name:"Nested lists", number1:"3", notes:"notes", "list.value1":"1.value1"]
    }

    void "Data models containing nested lists can be flattened"() {
        setup:
        def modelMetadata = new OutputMetadata(getJsonResource('nestedDataModel'))
        def modelData = getJsonResource('sampleNestedDataModel')

        when:
        List flat = outputModelProcessor.flatten2(modelData, modelMetadata)

        then:
        flat.size() == 5

        flat[0] == [name:"Nested lists", number1:"3", notes:"notes", "list.value1":"0.value1", "list.nestedList.value2":"0.0.value2"]
        flat[1] == [name:"Nested lists", number1:"3", notes:"notes", "list.value1":"0.value1", "list.nestedList.value2":"0.1.value2"]
        flat[2] == [name:"Nested lists", number1:"3", notes:"notes", "list.value1":"1.value1", "list.nestedList.value2":"1.0.value2"]
        flat[3] == [name:"Nested lists", number1:"3", notes:"notes", "list.value1":"1.value1", "list.nestedList.value2":"1.1.value2"]
        flat[4] == [name:"Nested lists", number1:"3", notes:"notes", "list.value1":"1.value1", "list.nestedList.value2":"1.2.value2"]



        // list.value1, list.nestedList.value2
        // 0.value1,    0.0.value2
        // 0.value1,    0.1.value2
        // 1.value1,    1.0.value2
        // 1.value1,    1.1.value2
        // 1.value1,    1.2.value2

    }


    private Map getJsonResource(name) {
        JSON.parse(getClass().getResourceAsStream("/resources/${name}.json"), 'UTF-8')
    }
}