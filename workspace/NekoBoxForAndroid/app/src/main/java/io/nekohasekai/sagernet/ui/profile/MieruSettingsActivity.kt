/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.compose.MieruProfileSettingsScreen

class MieruSettingsActivity : ProfileSettingsActivity<MieruBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = MieruBean().applyDefaultValues()

    override fun MieruBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverPorts = portRange
        DataStore.serverProtocolInt = protocol
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.serverMieruMuxLevel = multiplexingLevel
        DataStore.serverMieruHandshakeMode = handshakeMode
        DataStore.serverMieruTrafficPattern = trafficPattern
        DataStore.serverMieruLowEntropyMode = lowEntropyMode
        DataStore.serverMieruLowEntropyMaskRotation = lowEntropyMaskRotation
    }

    override fun MieruBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        portRange = DataStore.serverPorts
        protocol = DataStore.serverProtocolInt
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        multiplexingLevel = DataStore.serverMieruMuxLevel
        handshakeMode = DataStore.serverMieruHandshakeMode
        trafficPattern = DataStore.serverMieruTrafficPattern
        lowEntropyMode = DataStore.serverMieruLowEntropyMode
        lowEntropyMaskRotation = DataStore.serverMieruLowEntropyMaskRotation
    }

    @Composable
    override fun ComposePreferences() = MieruProfileSettingsScreen()

}
