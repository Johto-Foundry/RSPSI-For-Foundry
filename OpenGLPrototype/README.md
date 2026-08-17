# Foundry OpenGL Prototype

This module is an isolated staging area for replacing RSPSi's CPU software rasterizer with a GPU renderer while keeping the existing editor, cache plugins, map data and save logic intact.

Current milestone:

- JOGL/OpenGL 3 prototype window with uncapped animator and GPU information logging.
- Direct dependency on the working `Client` module instead of the abandoned rewrite's empty replacement classes.
- Read-only `CurrentSceneBridge` into the existing loaded chunk list.
- `TerrainMeshBuilder` converts a live 64x64 RSPSi chunk height map into indexed triangle geometry.
- `TerrainGpuBuffer` uploads that geometry to VAO/VBO/EBO storage ready for GPU drawing.

The prototype is intentionally not wired into the normal Editor startup yet. That keeps the production editor usable while GPU rendering is developed independently.

Run the standalone GL context test with:

`gradlew :OpenGLPrototype:run`

Build it with:

`gradlew :OpenGLPrototype:classes`

Next milestone is to wire a loaded `Client` scene into this module, upload one live terrain chunk, add a minimal shader/camera transform and display the same RSPSi terrain in OpenGL.
