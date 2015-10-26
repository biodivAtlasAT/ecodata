package au.org.ala.ecodata

import grails.converters.JSON

class ProjectActivityController {

    def projectActivityService

    def asJson = { model ->
        response.setContentType("application/json;charset=UTF-8")
        model
    }

    /**
     * Get all ProjectActivity by project id.
     *
     * @param id - project id
     * @return json
     */
    def getAllByProject(String id) {
        if (id) {
            def list = []
            list.addAll(projectActivityService.getAllByProject(id, params.view))
            asJson([list: list])
        } else {
            response.status = 404
            render status: 404, text: 'No such id'
        }
    }

    /**
     * Get projectActivity by id
     *
     * @param id - projectActivity id
     * @return json
     */
    def get(String id) {
        if (id) {
            asJson(projectActivityService.get(id, params.view))
        } else {
            response.status = 404
            render status: 404, text: 'No such id'
        }
    }

    /**
     * Delete project activity and all child records
     * @param id - project activity id
     * @param destroy = true to permanently delete the records, false to soft delete
     *
     * @return json map of
     */
    @RequireApiKey
    def delete(String id) {
        ProjectActivity pActivity = ProjectActivity.findByProjectActivityId(id)
        if (pActivity) {
            boolean destroy = params.destroy == null ? false : params.destroy.toBoolean()
            Map result = projectActivityService.delete(id, destroy)
            if (!result.error) {
                response.setStatus(200)
                response.setContentType("application/json")
                render(status: 200, text: 'deleted')
            } else {
                response.status = 400
                render(status: 400, text: result.error)
            }
        } else {
            response.status = 400
            render(status: 404, text: 'No such id')
        }
    }



    /**
     * Update a project activity.
     *
     * @param id - identifies the resource
     * @return
     */
    @RequireApiKey
    def update(String id) {
        def props = request.JSON
        log.debug props
        def result
        def message
        if (id) {
            result = projectActivityService.update(props,id)
            message = [message: 'updated']
        } else {
            result = projectActivityService.create(props)
            message = [message: 'created', projectActivityId: result.projectActivityId]
        }
        if (result.status == 'ok') {
            asJson(message)
        } else {
            log.error result.error
            render status:400, text: result.error
        }
    }

}
