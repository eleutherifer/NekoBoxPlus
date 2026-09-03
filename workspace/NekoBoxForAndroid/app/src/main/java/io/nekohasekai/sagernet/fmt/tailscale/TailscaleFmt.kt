package io.nekohasekai.sagernet.fmt.tailscale

import io.nekohasekai.sagernet.SagerNet
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

fun buildSingBoxEndpointTailscaleBean(
    bean: TailscaleBean,
    profileId: Long,
): SingBoxOptions.TailscaleEndpointOptions = SingBoxOptions.TailscaleEndpointOptions().apply {
    type = "tailscale"
    state_directory = "tailscale/$profileId"
    auth_key = bean.authKey.takeIf { it.isNotBlank() }
    control_url = bean.controlURL.takeIf { it.isNotBlank() }
    ephemeral = bean.ephemeral.takeIf { it }
    hostname = bean.hostname.takeIf { it.isNotBlank() }
    accept_routes = bean.acceptRoutes.takeIf { it }
    exit_node = bean.exitNode.takeIf { it.isNotBlank() }
    exit_node_allow_lan_access = bean.exitNodeAllowLANAccess.takeIf { it }
    advertise_routes = bean.advertiseRoutes.listByLineOrComma().takeIf { it.isNotEmpty() }
    advertise_exit_node = bean.advertiseExitNode.takeIf { it }
    advertise_tags = bean.advertiseTags.listByLineOrComma().takeIf { it.isNotEmpty() }
    relay_server_port = bean.relayServerPort.takeIf { it > 0 }
    relay_server_static_endpoints = bean.relayServerStaticEndpoints.listByLineOrComma().takeIf { it.isNotEmpty() }
    udp_timeout = bean.udpTimeout.takeIf { it.isNotBlank() }
}

fun deleteTailscaleProfileState(profileId: Long) {
    SagerNet.application.noBackupFilesDir.resolve("tailscale/$profileId").deleteRecursively()
}
