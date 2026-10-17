package com.example.blenderpicobridge.p7f82b4c1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in inspection of an already cached model. No pairing, scene or material changes. */
@RunWith(AndroidJUnit4::class)
class MaterialImportTest {
    @Test fun inspectCachedPbrMaterialImport()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("material_probe")=="true")
        val revision=requireNotNull(args.getString("model_revision"))
        require(revision.matches(Regex("[a-f0-9]{64}")))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"bridge-models/$revision.glb")
        assertTrue("Cached model required",file.isFile)
        withContext(Dispatchers.Main) {
            val root=Entity.loadSuspend(file)
            try {
                val output=JSONArray()
                val seen=mutableSetOf<String>()
                fun visit(entity: Entity) {
                    entity.components[ModelComponent::class.java]?.materials?.forEach { m ->
                        if(m is PhysicallyBasedMaterial && seen.add(m.getName())) {
                            fun texture(read: ()->com.pico.spatial.core.ecs.resource.TextureResource): Boolean =
                                runCatching { read().valid }.getOrDefault(false)
                            output.put(JSONObject().put("name",m.getName()).put("metallic",m.getMetallic())
                                .put("roughness",m.getRoughness()).put("baseColor",m.getBaseColor().toString())
                                .put("baseTexture",texture(m::getBaseColorTexture))
                                .put("roughnessTexture",texture(m::getRoughnessTexture))
                                .put("metallicTexture",texture(m::getMetallicTexture))
                                .put("normalTexture",texture(m::getNormalTexture)))
                        }
                    }
                    entity.getChildren().forEach(::visit)
                }
                visit(root)
                assertTrue("PBR materials expected",output.length()>0)
                File(context.filesDir,"material-probe.json").writeText(output.toString(2))
            } finally { root.destroy(true) }
        }
    }
}
