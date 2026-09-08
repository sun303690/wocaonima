package dev.sun.wechat.activity.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.sun.wechat.features.api.agent.WeAgentService
import dev.sun.wechat.agent.model.local.LocalLlama
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.agent.settings.ExternalServicesScreen
import dev.sun.wechat.ui.agent.settings.McpServerDetailScreen
import dev.sun.wechat.ui.agent.settings.McpServersScreen
import dev.sun.wechat.ui.agent.settings.LinuxEnvironmentDetailScreen
import dev.sun.wechat.ui.agent.settings.LinuxEnvironmentsScreen
import dev.sun.wechat.ui.agent.settings.LocalLlamaProviderDetailScreen
import dev.sun.wechat.ui.agent.settings.ModelDetailScreen
import dev.sun.wechat.ui.agent.settings.ModelProviderDetailScreen
import dev.sun.wechat.ui.agent.settings.ModelProvidersScreen
import dev.sun.wechat.ui.agent.settings.PromptsScreen
import dev.sun.wechat.ui.agent.settings.SkillsScreen
import dev.sun.wechat.ui.agent.settings.TriggersScreen
import dev.sun.wechat.ui.agent.settings.WeAgentHomeScreen
import dev.sun.wechat.ui.navigation.LocalNavigator
import dev.sun.wechat.ui.navigation.Navigator
import dev.sun.wechat.ui.navigation.rememberM3NavEffects
import dev.sun.wechat.ui.animation.predictiveback.weKitNavTransition
import dev.sun.wechat.ui.utils.theme.ModuleTheme
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * Dedicated WeAgent configuration Activity (§8). Deliberately separate from the floating overlay:
 * the overlay stays lean while all detailed configuration (model providers, MCP servers, tool
 * permissions, prompts, Linux environments, skills, global settings) lives here.
 *
 * Navigation mirrors [dev.sun.wechat.activity.settings.SettingsActivity]: a miuix-nav
 * [NavDisplay] stack with the predictive-back drill-down transition, supporting arbitrary depth.
 *
 * Named `*SettingsActivity` so [dev.sun.wechat.loader.utils.ActivityProxy] routes it through the
 * opaque splash proxy when launched from WeChat's host process.
 */
@Keep
class WeAgentSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ensure the backend is initialized even if the overlay feature hasn't been toggled yet.
        WeAgentService.init()

        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                val dark = ThemeSettings.themeMode.resolve()
                ModuleTheme(darkTheme = dark) {
                    WeAgentSettingsRoot(onFinish = { finish() })
                }
            }
        }
    }
}

/** In-Activity navigation targets. [Home] is the stack root and also hosts global settings. */
@Serializable
sealed interface AgentSettingsRoute : NavKey {
    @Serializable
    data object Home : AgentSettingsRoute
    @Serializable
    data object ModelProviders : AgentSettingsRoute
    @Serializable
    data class ModelProviderDetail(val providerId: String) : AgentSettingsRoute
    @Serializable
    data class ModelDetail(val providerId: String, val modelId: String) : AgentSettingsRoute
    @Serializable
    data object McpServers : AgentSettingsRoute
    @Serializable
    data class McpServerDetail(val serverId: String) : AgentSettingsRoute
    @Serializable
    data object Prompts : AgentSettingsRoute
    @Serializable
    data object LinuxEnvironments : AgentSettingsRoute
    @Serializable
    data class LinuxEnvironmentDetail(val environmentId: String?) : AgentSettingsRoute
    @Serializable
    data object Skills : AgentSettingsRoute
    @Serializable
    data object Triggers : AgentSettingsRoute
    @Serializable
    data object ExternalServices : AgentSettingsRoute
}

@Composable
private fun WeAgentSettingsRoot(onFinish: () -> Unit) {
    val backStack = rememberNavBackStack<AgentSettingsRoute>(AgentSettingsRoute.Home)
    val navigator = remember(backStack) { Navigator(backStack) }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = {
                if (navigator.backStackSize() <= 1) onFinish() else navigator.pop()
            },
            transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<AgentSettingsRoute.Home> {
                WeAgentHomeScreen(onOpen = { navigator.push(it) })
            }
            entry<AgentSettingsRoute.ModelProviders>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                ModelProvidersScreen(
                    onBack = { navigator.pop() },
                    onOpenProvider = { navigator.push(AgentSettingsRoute.ModelProviderDetail(it)) },
                )
            }
            entry<AgentSettingsRoute.ModelProviderDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                if (key.providerId == LocalLlama.PROVIDER_ID) {
                    LocalLlamaProviderDetailScreen(
                        onOpenModel = { providerId, modelId ->
                            navigator.push(AgentSettingsRoute.ModelDetail(providerId, modelId))
                        },
                        onBack = { navigator.pop() },
                    )
                } else {
                    ModelProviderDetailScreen(
                        providerId = key.providerId,
                        // providerId comes from the screen, not the route key: after creating a
                        // provider in place the route entry still carries its blank creation id.
                        onOpenModel = { providerId, modelId ->
                            navigator.push(AgentSettingsRoute.ModelDetail(providerId, modelId))
                        },
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<AgentSettingsRoute.ModelDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                ModelDetailScreen(providerId = key.providerId, modelId = key.modelId, onBack = { navigator.pop() })
            }
            entry<AgentSettingsRoute.McpServers>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                McpServersScreen(
                    onBack = { navigator.pop() },
                    onOpenServer = { navigator.push(AgentSettingsRoute.McpServerDetail(it)) },
                )
            }
            entry<AgentSettingsRoute.McpServerDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                McpServerDetailScreen(serverId = key.serverId, onBack = { navigator.pop() })
            }
            entry<AgentSettingsRoute.Prompts>(swipeDismiss = NavSwipeDirection.LeftToRight) { PromptsScreen(onBack = { navigator.pop() }) }
            entry<AgentSettingsRoute.LinuxEnvironments>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                LinuxEnvironmentsScreen(
                    onBack = { navigator.pop() },
                    onOpen = { navigator.push(AgentSettingsRoute.LinuxEnvironmentDetail(it)) },
                )
            }
            entry<AgentSettingsRoute.LinuxEnvironmentDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                LinuxEnvironmentDetailScreen(environmentId = key.environmentId, onBack = { navigator.pop() })
            }
            entry<AgentSettingsRoute.Skills>(swipeDismiss = NavSwipeDirection.LeftToRight) { SkillsScreen(onBack = { navigator.pop() }) }
            entry<AgentSettingsRoute.Triggers>(swipeDismiss = NavSwipeDirection.LeftToRight) { TriggersScreen(onBack = { navigator.pop() }) }
            entry<AgentSettingsRoute.ExternalServices>(swipeDismiss = NavSwipeDirection.LeftToRight) { ExternalServicesScreen(onBack = { navigator.pop() }) }
        }
    }
}
