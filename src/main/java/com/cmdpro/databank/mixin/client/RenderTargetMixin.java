package com.cmdpro.databank.mixin.client;

import com.cmdpro.databank.Databank;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

import static com.mojang.blaze3d.platform.GlConst.GL_DRAW_FRAMEBUFFER;
import static com.mojang.blaze3d.platform.GlConst.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_INTERNAL_FORMAT;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
    @Unique
    private static final boolean databank$skipDepthBlits =
        Boolean.getBoolean("databank.skipDepthBlits")
            || Boolean.getBoolean("databank.skipPostShaderDepthCopies");

    @Unique
    private static boolean databank$depthBlitsDisabled;

    @Unique
    private static boolean databank$warningLogged;

    @Unique
    private static String databank$pendingSource;

    @Unique
    private static String databank$pendingDestination;


    @Inject(method = "copyDepthFrom", at = @At("HEAD"), cancellable = true, remap = false)
    private void databank$beforeCopyDepth(RenderTarget source, CallbackInfo ci) {
        if (!databank$beforeCopyDepth(source, (RenderTarget)(Object)this)) {
            ci.cancel();
        }
    }

    @Inject(method = "copyDepthFrom", at = @At("RETURN"), remap = false)
    private void databank$afterCopyDepth(RenderTarget source, CallbackInfo ci) {
        databank$afterCopyDepth();
    }

    @Unique
    private static boolean databank$beforeCopyDepth(RenderTarget source, RenderTarget destination) {
        databank$pendingSource = null;
        databank$pendingDestination = null;
        if (databank$depthBlitsDisabled) {
            return false;
        }

        int pendingError = GlStateManager._getError();
        int[] sourceInfo = databank$getDepthAttachmentInfo(source);
        int[] destinationInfo = databank$getDepthAttachmentInfo(destination);
        String sourceDescription = databank$describeDepthAttachment(sourceInfo);
        String destinationDescription = databank$describeDepthAttachment(destinationInfo);

        if (pendingError != GL11.GL_NO_ERROR) {
            databank$disableDepthBlits(
                "a GL error was already pending; this blit was skipped and the earlier source is unknown",
                pendingError,
                sourceDescription,
                destinationDescription
            );
            return false;
        }

        int inspectionError = GlStateManager._getError();
        if (inspectionError != GL11.GL_NO_ERROR) {
            databank$disableDepthBlits(
                "attachment inspection generated a GL error; this blit was skipped",
                inspectionError,
                sourceDescription,
                destinationDescription
            );
            return false;
        }

        String problem = databank$getDepthCopyProblem(sourceInfo, destinationInfo);
        if (databank$skipDepthBlits) {
            problem = "disabled by the Databank depth-blit JVM property";
        }
        if (problem != null) {
            databank$disableDepthBlits(problem, GL11.GL_NO_ERROR, sourceDescription, destinationDescription);
            return false;
        }

        databank$pendingSource = sourceDescription;
        databank$pendingDestination = destinationDescription;
        return true;
    }

    @Unique
    private static void databank$afterCopyDepth() {
        String source = databank$pendingSource;
        String destination = databank$pendingDestination;
        databank$pendingSource = null;
        databank$pendingDestination = null;
        if (databank$depthBlitsDisabled || source == null || destination == null) {
            return;
        }

        int error = GlStateManager._getError();
        if (error != GL11.GL_NO_ERROR) {
            databank$disableDepthBlits(
                "the depth blit generated a GL error after queried attachment properties matched",
                error,
                source,
                destination
            );
        }
    }

    @Unique
    private static void databank$disableDepthBlits(
        String reason,
        int glError,
        String source,
        String destination
    ) {
        databank$depthBlitsDisabled = true;
        if (databank$warningLogged) {
            return;
        }

        String errorDescription = glError == GL11.GL_NO_ERROR
            ? "none"
            : glError + " (0x" + Integer.toHexString(glError) + ")";
        Databank.LOGGER.warn(
            "Disabling all Databank depth blits for the rest of this session: {}; GL error: {}; caller: {}; source: {}; destination: {}",
            reason, errorDescription, databank$callerDescription(), source, destination
        );
        databank$warningLogged = true;
    }

    @Unique
    private static String databank$getDepthCopyProblem(int[] source, int[] destination) {
        if (source == null || destination == null) {
            return "one or both depth attachments are missing or incomplete";
        }

        List<String> mismatches = new ArrayList<>();
        if (source[3] != destination[3] || source[4] != destination[4]) {
            mismatches.add(
                "dimensions differ: source=" + source[3] + "x" + source[4]
                    + ", destination=" + destination[3] + "x" + destination[4]
            );
        }
        if (source[5] != destination[5]) {
            mismatches.add(
                "texture formats differ: source=" + databank$formatDescription(source[5])
                    + ", destination=" + databank$formatDescription(destination[5])
            );
        }
        if (source[6] != destination[6]) {
            mismatches.add("depth bits differ: source=" + source[6] + ", destination=" + destination[6]);
        }
        if (source[7] != destination[7]) {
            mismatches.add("component types differ: source=" + source[7] + ", destination=" + destination[7]);
        }
        if (source[8] != destination[8]) {
            mismatches.add("stencil bits differ: source=" + source[8] + ", destination=" + destination[8]);
        }
        if (source[9] != destination[9] || source[10] != destination[10]) {
            mismatches.add(
                "sample counts differ: source=" + source[9] + "/" + source[10]
                    + ", destination=" + destination[9] + "/" + destination[10]
            );
        }
        return mismatches.isEmpty() ? null : String.join("; ", mismatches);
    }

    @Unique
    private static String databank$describeDepthAttachment(int[] info) {
        if (info == null) {
            return "null";
        }
        return "DepthAttachmentInfo[framebuffer=" + info[0]
            + ", attachmentType=" + info[1]
            + ", attachment=" + info[2]
            + ", width=" + info[3]
            + ", height=" + info[4]
            + ", textureFormat=" + databank$formatDescription(info[5])
            + ", depthBits=" + info[6]
            + ", componentType=" + info[7]
            + ", stencilBits=" + info[8]
            + ", sampleBuffers=" + info[9]
            + ", samples=" + info[10]
            + ", stencilEnabled=" + (info[11] != 0) + "]";
    }

    @Unique
    private static int[] databank$getDepthAttachmentInfo(RenderTarget target) {
        int previousDrawFramebuffer = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousTexture = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            if (GlStateManager.glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                return null;
            }

            int attachmentType = GL30.glGetFramebufferAttachmentParameteri(
                GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
            );
            if (attachmentType == GL11.GL_NONE || target.getDepthTextureId() < 0) {
                return null;
            }

            GlStateManager._bindTexture(target.getDepthTextureId());
            int textureFormat = GlStateManager._getTexLevelParameter(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            return new int[] {
                target.frameBufferId,
                attachmentType,
                GL30.glGetFramebufferAttachmentParameteri(
                    GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
                ),
                target.width,
                target.height,
                textureFormat,
                GL30.glGetFramebufferAttachmentParameteri(
                    GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE
                ),
                GL30.glGetFramebufferAttachmentParameteri(
                    GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE
                ),
                GL30.glGetFramebufferAttachmentParameteri(
                    GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE
                ),
                GL11.glGetInteger(GL13.GL_SAMPLE_BUFFERS),
                GL11.glGetInteger(GL13.GL_SAMPLES),
                target.isStencilEnabled() ? 1 : 0,
            };
        } finally {
            GlStateManager._bindTexture(previousTexture);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        }
    }

    @Unique
    private static String databank$formatDescription(int format) {
        String name = switch (format) {
            case GL11.GL_DEPTH_COMPONENT -> "GL_DEPTH_COMPONENT";
            case GL30.GL_DEPTH24_STENCIL8 -> "GL_DEPTH24_STENCIL8";
            case GL30.GL_DEPTH32F_STENCIL8 -> "GL_DEPTH32F_STENCIL8";
            default -> "unknown";
        };
        return name + " (" + format + ", 0x" + Integer.toHexString(format) + ")";
    }

    @Unique
    private static String databank$callerDescription() {
        return StackWalker.getInstance().walk(frames -> frames
            .filter(frame -> !frame.getClassName().equals(RenderTargetMixin.class.getName()))
            .filter(frame -> !frame.getClassName().equals("com.mojang.blaze3d.pipeline.RenderTarget"))
            .findFirst()
            .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
            .orElse("unknown")
        );
    }
}
