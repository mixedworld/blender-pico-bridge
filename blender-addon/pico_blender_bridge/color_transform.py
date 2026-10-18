"""Scene-linear HDR to SDR video using Blender's active OpenColorIO configuration.

Only straight RGB is colour-managed. Coverage stays linear and unchanged. The
returned bytes are associated RGBA, matching the encoder's resampling contract.
This module deliberately has no bpy/GPU imports so numerical tests can run alone.
"""
import math


class ColorTransformError(RuntimeError):
    pass


class DisplayTransform:
    def __init__(self, config=None):
        try:
            import PyOpenColorIO as ocio
        except ImportError as error:
            raise ColorTransformError("Cycles streaming needs Blender's PyOpenColorIO module") from error
        self.ocio = ocio
        self.config = config or ocio.GetCurrentConfig()
        self.key = None
        self.cpu = None

    def processor(self, *, view, look="None", exposure=0.0, gamma=1.0,
                  display="sRGB", working="scene_linear"):
        if not math.isfinite(exposure) or not math.isfinite(gamma) or gamma <= 0:
            raise ColorTransformError("Invalid Blender exposure or gamma for streaming")
        key = (self.config.getCacheID(), view, look, exposure, gamma, display, working)
        if key != self.key:
            ocio = self.ocio
            try:
                # LegacyViewingPipeline exposes OCIO's public look override, with exposure
                # before the view transform and display gamma afterwards, as Blender does.
                pipeline = ocio.LegacyViewingPipeline()
                pipeline.setDisplayViewTransform(ocio.DisplayViewTransform(
                    src=working, display=display, view=view))
                pipeline.setLinearCC(ocio.MatrixTransform.Scale([2.0 ** exposure] * 3 + [1.0]))
                pipeline.setDisplayCC(ocio.ExponentTransform(value=[1.0 / gamma] * 3 + [1.0]))
                pipeline.setLooksOverride("" if look in ("", "None") else look)
                pipeline.setLooksOverrideEnabled(look not in ("", "None"))
                base = pipeline.getProcessor(self.config)
                # The existing SDR video receiver consumes sRGB/Rec.709 colour, even if
                # Blender is previewing a different display gamut. Do not relabel P3 as sRGB.
                if display != "sRGB":
                    source = self.config.getDisplayViewColorSpaceName(display, view)
                    target = self.config.getDisplayViewColorSpaceName("sRGB", "Standard")
                    group = ocio.GroupTransform()
                    group.appendTransform(base.createGroupTransform())
                    group.appendTransform(ocio.ColorSpaceTransform(src=source, dst=target))
                    base = self.config.getProcessor(group)
                self.cpu = base.getDefaultCPUProcessor()
            except Exception as error:
                raise ColorTransformError("Blender's selected colour view cannot be converted to SDR video") from error
            self.key = key
        return self.cpu

    def convert(self, rgba, *, processor=None, **settings):
        import numpy as np
        # GPU data is associated scene-linear float, including values above one.
        # Unassociate *before* the nonlinear view transform; quantize only afterwards.
        pixels = np.array(rgba, dtype=np.float32, order="C", copy=True)
        alpha = np.clip(np.nan_to_num(pixels[..., 3:4], nan=0.0), 0.0, 1.0).copy()
        np.divide(pixels[..., :3], alpha, out=pixels[..., :3], where=alpha > 1e-8)
        pixels[..., :3] = np.where(alpha > 1e-8, pixels[..., :3], 0.0)
        pixels[..., 3:4] = alpha
        cpu = processor if processor is not None else self.processor(**settings)
        cpu.applyRGBA(pixels)
        rgb = np.clip(np.nan_to_num(pixels[..., :3], nan=0.0), 0.0, 1.0)
        pixels[..., :3] = rgb * alpha
        pixels[..., 3:4] = alpha
        return np.rint(pixels * 255.0).astype(np.uint8)
