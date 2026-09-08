package dev.sun.wechat.features.items.system

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.sun.wechat.R
import dev.sun.wechat.BuildConfig
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import java.lang.reflect.Field

object PreventModuleDataDeletion : SwitchFeature(), IResolveDex {

    override val technicalId = "阻止微信清理模块数据"
    override val nameRes = R.string.feature_prevent_module_data_deletion_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_prevent_module_data_deletion_description

    private val methodNativeFileSystemEntryDelete by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("VFS.NativeFileSystem", "Base directory exists but is not a directory, delete and proceed.Base path: ")
            }

            paramTypes(String::class.java)
            returnType = "boolean"

            invokeMethods {
                add {
                    declaredClass = "java.io.File"
                    name = "delete"
                }
            }
        }
    }
    private lateinit var basePathField: Field

    override fun onEnable() {
        methodNativeFileSystemEntryDelete.hookBefore {
            val relPath = args[0] as String
            if (!::basePathField.isInitialized) {
                basePathField = thisObject!!.reflekt()
                    .firstField {
                        type = String::class
                        modifiers(Modifiers.FINAL)
                    }.self
            }
            val basePath = basePathField.get(thisObject) as String

            val path = "$basePath/$relPath"
            if (path.contains(BuildConfig.TAG) || path.contains("Layout Inspect")) {
                result = true
            }
        }
    }
}
