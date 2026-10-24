package io.github.vinterus.halite.client.gl;

import io.github.vinterus.halite.client.HaliteClientMod;
import org.lwjgl.opengl.ARBVertexBufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;

/**
 * Thin wrapper over the GL entry points the chunk renderer needs. Everything here is restricted to what
 * OpenGL 1.5 / GLES 2.0 (through gl4es) can provide: buffer objects and client-state vertex arrays. No VAOs,
 * no base-vertex draws, no persistent mapping.
 */
public final class GlCompat {
    public static final int ARRAY_BUFFER = 0x8892;
    public static final int ELEMENT_ARRAY_BUFFER = 0x8893;
    public static final int STATIC_DRAW = 0x88E4;

    private static boolean initialized;
    private static boolean core15;
    private static boolean arbVbo;
    private static boolean useVbo;
    private static boolean gl4es;
    private static String renderer = "unknown";

    private GlCompat() {
    }

    public static void init(boolean allowVbo) {
        ContextCapabilities caps = GLContext.getCapabilities();

        core15 = caps.OpenGL15;
        arbVbo = caps.GL_ARB_vertex_buffer_object;

        String vendor = String.valueOf(GL11.glGetString(GL11.GL_VENDOR));
        renderer = String.valueOf(GL11.glGetString(GL11.GL_RENDERER));
        String version = String.valueOf(GL11.glGetString(GL11.GL_VERSION));

        gl4es = renderer.toLowerCase().contains("gl4es") || version.toLowerCase().contains("gl4es")
                || vendor.toLowerCase().contains("ptitseb");

        useVbo = allowVbo && (core15 || arbVbo);
        initialized = true;

        HaliteClientMod.logger().info("GL: {} / {} / {} (VBO: {}, gl4es: {})", vendor, renderer, version,
                useVbo ? (core15 ? "core" : "ARB") : "disabled", gl4es);
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean useVbo() {
        return useVbo;
    }

    public static boolean isGl4es() {
        return gl4es;
    }

    public static String getRenderer() {
        return renderer;
    }

    public static int genBuffer() {
        return core15 ? GL15.glGenBuffers() : ARBVertexBufferObject.glGenBuffersARB();
    }

    public static void deleteBuffer(int id) {
        if (core15) {
            GL15.glDeleteBuffers(id);
        } else {
            ARBVertexBufferObject.glDeleteBuffersARB(id);
        }
    }

    public static void bindBuffer(int target, int id) {
        if (core15) {
            GL15.glBindBuffer(target, id);
        } else {
            ARBVertexBufferObject.glBindBufferARB(target, id);
        }
    }

    public static void bufferData(int target, ByteBuffer data, int usage) {
        if (core15) {
            GL15.glBufferData(target, data, usage);
        } else {
            ARBVertexBufferObject.glBufferDataARB(target, data, usage);
        }
    }

    /** Allocates uninitialised storage for the bound buffer. */
    public static void bufferData(int target, long size, int usage) {
        if (core15) {
            GL15.glBufferData(target, size, usage);
        } else {
            ARBVertexBufferObject.glBufferDataARB(target, size, usage);
        }
    }

    public static void bufferSubData(int target, long offset, ByteBuffer data) {
        if (core15) {
            GL15.glBufferSubData(target, offset, data);
        } else {
            ARBVertexBufferObject.glBufferSubDataARB(target, offset, data);
        }
    }
}
