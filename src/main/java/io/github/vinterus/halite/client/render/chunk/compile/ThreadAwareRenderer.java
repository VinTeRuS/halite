package io.github.vinterus.halite.client.render.chunk.compile;

/**
 * Added by mixin to StationAPI's baked model renderer when Halite managed to make it forward block rendering on
 * chunk builder threads to a per-thread instance. Without it, meshing stays on the render thread.
 */
public interface ThreadAwareRenderer {
}
