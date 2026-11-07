package com.handgame.prototype.render

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder

/**
 * Materials are written as shader snippets here and compiled on the device at startup with
 * filamat, so there is no offline material-compiler step.
 */
object Materials {

    /**
     * Soft vinyl/plastic toy look: non-metallic base, gentle roughness and a clear-coat layer for
     * the glossy-but-gentle sheen, plus a soft rim light. `glow` (rgb, strength) drives the
     * grab-range highlight.
     */
    private const val TOY_SHADER = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            vec3 base = materialParams.baseColor;
            material.baseColor = vec4(base, 1.0);
            material.metallic = 0.0;
            material.roughness = materialParams.roughness;
            material.clearCoat = 0.8;
            material.clearCoatRoughness = 0.3;
            float rim = 1.0 - saturate(dot(shading_normal, shading_view));
            rim = rim * rim * rim;
            vec3 glow = materialParams.glow.rgb * materialParams.glow.a;
            material.emissive = vec4(glow + base * rim * 0.25, 0.0);
        }
    """

    private const val GRADIENT_SHADER = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            material.baseColor = getColor();
        }
    """

    /** Flat colour drawn on back faces only: used for inverted-hull cartoon outlines. */
    private const val OUTLINE_SHADER = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            material.baseColor = vec4(materialParams.color, 1.0);
        }
    """

    fun init() = MaterialBuilder.init()

    fun outline(engine: Engine): Material {
        val pkg = MaterialBuilder()
            .name("outline")
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.OPENGL)
            .optimization(MaterialBuilder.Optimization.PERFORMANCE)
            .shading(MaterialBuilder.Shading.UNLIT)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "color")
            .culling(MaterialBuilder.CullingMode.FRONT)
            .material(OUTLINE_SHADER)
            .build()
        check(pkg.isValid) { "Outline material failed to compile" }
        return Material.Builder().payload(pkg.buffer, pkg.buffer.remaining()).build(engine)
    }

    fun toy(engine: Engine): Material {
        val pkg = MaterialBuilder()
            .name("toy")
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.OPENGL)
            .optimization(MaterialBuilder.Optimization.PERFORMANCE)
            .shading(MaterialBuilder.Shading.LIT)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "baseColor")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "roughness")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "glow")
            .material(TOY_SHADER)
            .build()
        check(pkg.isValid) { "Toy material failed to compile" }
        return Material.Builder().payload(pkg.buffer, pkg.buffer.remaining()).build(engine)
    }

    fun gradient(engine: Engine): Material {
        val pkg = MaterialBuilder()
            .name("gradient")
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.OPENGL)
            .optimization(MaterialBuilder.Optimization.PERFORMANCE)
            .shading(MaterialBuilder.Shading.UNLIT)
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .culling(MaterialBuilder.CullingMode.NONE)
            .material(GRADIENT_SHADER)
            .build()
        check(pkg.isValid) { "Gradient material failed to compile" }
        return Material.Builder().payload(pkg.buffer, pkg.buffer.remaining()).build(engine)
    }
}
