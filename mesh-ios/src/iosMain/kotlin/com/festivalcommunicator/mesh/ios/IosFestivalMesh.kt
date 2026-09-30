package com.festivalcommunicator.mesh.ios

import com.festivalcommunicator.mesh.FestivalMesh
import com.festivalcommunicator.mesh.MeshConfig
import com.festivalcommunicator.mesh.MeshNetwork
import com.festivalcommunicator.mesh.NodeIdentity
import com.festivalcommunicator.mesh.NodeIdentityFactory

object IosFestivalMesh {
    fun create(
        identity: NodeIdentity = NodeIdentityFactory.generate(),
        config: MeshConfig = MeshConfig(),
    ): MeshNetwork = FestivalMesh.create(
        identity = identity,
        transport = IosBleMeshTransport(),
        config = config,
    )
}
