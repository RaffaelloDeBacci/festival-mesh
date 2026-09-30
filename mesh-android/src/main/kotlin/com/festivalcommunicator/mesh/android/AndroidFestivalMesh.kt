package com.festivalcommunicator.mesh.android

import android.content.Context
import com.festivalcommunicator.mesh.FestivalMesh
import com.festivalcommunicator.mesh.MeshConfig
import com.festivalcommunicator.mesh.MeshNetwork
import com.festivalcommunicator.mesh.NodeIdentity
import com.festivalcommunicator.mesh.NodeIdentityFactory

object AndroidFestivalMesh {
    fun create(
        context: Context,
        identity: NodeIdentity = NodeIdentityFactory.generate(),
        config: MeshConfig = MeshConfig(),
    ): MeshNetwork = FestivalMesh.create(
        identity = identity,
        transport = AndroidBleMeshTransport(context),
        config = config,
    )
}
