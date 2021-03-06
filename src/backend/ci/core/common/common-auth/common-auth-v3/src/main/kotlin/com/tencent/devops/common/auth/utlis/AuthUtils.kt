package com.tencent.devops.common.auth.utlis

import com.tencent.bk.sdk.iam.constants.ExpressionOperationEnum
import com.tencent.bk.sdk.iam.dto.expression.ExpressionDTO
import com.tencent.devops.common.auth.api.AuthResourceType

object AuthUtils {

    fun getProjects(content: ExpressionDTO): List<String> {
        if (content.field != "project.id") {
            if (content.operator != ExpressionOperationEnum.ANY) {
                return emptyList()
            }
        }
        val projectList = mutableListOf<String>()
        when (content.operator) {
            ExpressionOperationEnum.ANY -> projectList.add("*")
            ExpressionOperationEnum.EQUAL -> projectList.add(content.value.toString())
            ExpressionOperationEnum.IN -> projectList.addAll(StringUtils.obj2List(content.value.toString()))
        }
        return projectList
    }

    fun getResourceInstance(
        content: List<ExpressionDTO>,
        projectId: String,
        resourceType: AuthResourceType
    ): Set<String> {
        val instantList = mutableSetOf<String>()
        content.map {
            val field = it.field
            val op = it.operator
            if (!field.contains("_bk_iam_path_") || op != ExpressionOperationEnum.START_WITH) {
                return@map
            }
            val value = it.value.toString().split(",")
            if (value[0] != "/project") {
                return@map
            }

            // 选中了项目下 “无限制”选项
            if (value.size == 2) {
                if (value[1].substringBefore("/") == projectId) {
                    return setOf("*")
                } else {
                    return@map
                }
            }

            // 选中了项目下某资源的 特定实例
            // 如 /project,projectA/pipeline,pipelineB/
            if (value[1].substringBefore("/") != projectId || value[1].substringAfter("/") != resourceType.value) {
                return@map
            }
            val instance = value[2].substringBefore("/")
            if (instance == "*") {
                return setOf("*")
            } else {
                instantList.add(value[2].substringBefore("/"))
            }
        }
        return instantList
    }

    // 无content怎么处理 一层怎么处理,二层怎么处理。 默认只有两层。
    fun getResourceInstance(expression: ExpressionDTO, projectId: String, resourceType: AuthResourceType): Set<String> {
        val instantList = mutableSetOf<String>()
        // 项目下无限制 {"field":"pipeline._bk_iam_path_","op":"starts_with","value":"/project,test1/"}
        if (expression.content == null || expression.content.isEmpty()) {
            instantList.addAll(getInstanceByField(expression, projectId, resourceType))
        } else {
            instantList.addAll(getInstanceByContent(expression.content, expression, projectId, resourceType))
        }

        // 单个项目下有特定资源若干实例
        // [{"field":"pipeline.id","op":"in","value":["p-098b68a251ae4ec4b6f4fde87767387f","p-12b2c343109f43a58a79dcb9e3721c1b","p-54a8619d1f754d32b5b2bc249a74f26c"]},{"field":"pipeline._bk_iam_path_","op":"starts_with","value":"/project,demo/"}]

        // 多个项目下有特定资源若干实例
        // [{"content":[{"field":"pipeline.id","op":"in","value":["p-0d1fff4dabca4fc282e5ff63644bd339","p-54fb8b6562584df4b3693f7c787c105a"]},{"field":"pipeline._bk_iam_path_","op":"starts_with","value":"/project,v3test/"}],"op":"AND"},{"content":[{"field":"pipeline.id","op":"in","value":["p-098b68a251ae4ec4b6f4fde87767387f","p-12b2c343109f43a58a79dcb9e3721c1b","p-54a8619d1f754d32b5b2bc249a74f26c"]},{"field":"pipeline._bk_iam_path_","op":"starts_with","value":"/project,demo/"}],"op":"AND"}]

        // 多个项目下有特定资源权限,且有项目勾选任意
        // [{"field":"pipeline._bk_iam_path_","op":"starts_with","value":"/project,demo/"},{"content":[{"field":"pipeline.id","op":"in","value":["p-0d1fff4dabca4fc282e5ff63644bd339","p-54fb8b6562584df4b3693f7c787c105a"]},{"field":"pipeline._bk_iam_path_","op":"starts_with","value":"/project,v3test/"}],"op":"AND"}]
        return instantList
    }

    private fun getInstanceByContent(
        childExpression: List<ExpressionDTO>,
        parentExpression: ExpressionDTO,
        projectId: String,
        resourceType: AuthResourceType
    ): Set<String> {
        val instantList = mutableSetOf<String>()
        when (parentExpression.operator) {
            ExpressionOperationEnum.AND -> instantList.addAll(
                getInstanceByContent(
                    childExpression,
                    projectId,
                    resourceType
                )
            )
            ExpressionOperationEnum.OR -> instantList.addAll(
                getInstanceByContent(
                    childExpression,
                    projectId,
                    resourceType
                )
            )
        }
        return instantList
    }

    private fun getInstanceByContent(
        childExpression: List<ExpressionDTO>,
        projectId: String,
        resourceType: AuthResourceType
    ): Set<String> {
        var cacheList = mutableSetOf<String>()
        var isReturn = false
        var successCount = 0
        childExpression.map {
            if (it.content != null && it.content.isNotEmpty()) {
                val childInstanceList = getInstanceByContent(it.content, projectId, resourceType)
                if (childInstanceList.isNotEmpty()) {
                    cacheList.addAll(childInstanceList)
                    isReturn = true
                    successCount += 1
                }
                return@map
            }

            if (!checkField(it.field, resourceType)) {
                return@map
            }
            when (it.operator) {
                ExpressionOperationEnum.IN -> {
                    cacheList.addAll(StringUtils.obj2List(it.value.toString()))
                    StringUtils.removeAllElement(cacheList)
                }
                ExpressionOperationEnum.EQUAL -> {
                    cacheList.add(it.value.toString())
                    StringUtils.removeAllElement(cacheList)
                }
                ExpressionOperationEnum.START_WITH -> {
                    val startWithPair = checkProject(projectId, it)
                    isReturn = startWithPair.first
                    if (isReturn && cacheList.size == 0) {
                        cacheList.addAll(startWithPair.second)
                    }
                }
                else -> cacheList = emptySet<String>() as MutableSet<String>
            }
        }

        return when {
            isReturn -> {
                cacheList
            }
            successCount > 0 -> {
                cacheList
            }
            else -> {
                emptySet()
            }
        }
    }

    fun getInstanceByField(expression: ExpressionDTO, projectId: String, resourceType: AuthResourceType): Set<String> {
        val instanceList = mutableSetOf<String>()
        val value = expression.value

        if (!checkField(expression.field, resourceType)) {
            return emptySet()
        }

        when (expression.operator) {
            ExpressionOperationEnum.ANY -> instanceList.add("*")
            ExpressionOperationEnum.EQUAL -> instanceList.add(value.toString())
            ExpressionOperationEnum.IN -> instanceList.addAll(StringUtils.obj2List(value.toString()))
            ExpressionOperationEnum.START_WITH -> {
                instanceList.addAll(checkProject(projectId, expression).second)
            }
        }

        return instanceList
    }

    private fun checkProject(projectId: String, expression: ExpressionDTO): Pair<Boolean, Set<String>> {
        val instanceList = mutableSetOf<String>()
        val values = expression.value.toString().split(",")
        if (values[0] != "/project") {
            return Pair(false, emptySet())
        }
        if (values[1].substringBefore("/") != projectId) {
            return Pair(false, emptySet())
        }
        instanceList.add("*")
        return Pair(true, instanceList)
    }

    private fun checkField(field: String, resourceType: AuthResourceType): Boolean {
        if (field.contains(resourceType.value)) {
            return true
        }
        return false
    }
}