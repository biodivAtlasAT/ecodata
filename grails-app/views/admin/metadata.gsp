<%@ page import="grails.converters.JSON; org.apache.commons.lang.StringEscapeUtils" %>
<!doctype html>
<html>
    <head>
        <meta name="layout" content="adminLayout"/>
        <title>Metadata | Admin | Data capture | Atlas of Living Australia</title>
    </head>

    <body>

        <content tag="pageTitle">Metadata</content>
        <table class="table table-bordered">
            <thead>
                <tr>
                    <th><span style="font-size: large">Activities model</span>
                    </th>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <td style="padding:0;">
                        <pre id="activitiesMetadata" style="margin:0;width:97%;">${activitiesMetadata}</pre>
                        <textarea style="display:none;width:97%;min-height:400px;"></textarea>
                    </td>
                </tr>
            </tbody>
        </table>

        <asset:script>
            $(function(){
                $('#activitiesMetadata').html(JSON.stringify(${raw((activitiesMetadata as JSON).toString())},null, 2));

                // assume only one edit field for now
                var $pre = $('#activitiesMetadata'),
                    $textarea = $pre.next(),
                    $viewBtns = $('#viewModeBtns'),
                    $editBtns = $('#editModeBtns');
                $('#btnEdit').click(function () {
                    editMode();
                });
                $('#btnCancel').click(function () {
                    viewMode();
                });
                $('#btnSave').click(function () {
                    var text = $textarea.val();
                    $.ajax("${createLink(action: 'updateActivitiesModel')}", {
                        type: 'POST',
                        data: $textarea.val(),
                        contentType: 'application/json',
                        success: function (data) {
                            if (data !== 'error') {
                                $pre.html(JSON.stringify(data,null,2));
                                viewMode();
                            } else {
                                alert(data);
                            }
                        },
                        dataType: 'text'
                    });
                });
                function editMode() {
                    // replace pre element with textarea element
                    $textarea.html($pre.html());
                    $pre.slideUp();
                    $textarea.css('height',$pre.css('height'));
                    $textarea.slideDown();
                    // show edit mode buttons
                    $viewBtns.hide();
                    $editBtns.show();
                }
                function viewMode() {
                    $textarea.slideUp();
                    $pre.slideDown();
                    $editBtns.hide();
                    $viewBtns.show();
                }
            });
        </asset:script>
    </body>
</html>