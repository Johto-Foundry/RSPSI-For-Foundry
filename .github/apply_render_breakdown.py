from pathlib import Path

path = Path('Client/src/main/java/com/jagex/Client.java')
text = path.read_text()

field = '\tprivate long lastPerfConsoleLog = 0L;'
if field not in text:
    raise SystemExit('Could not find console profiler field')
if 'lastRenderBreakdownLog' not in text:
    text = text.replace(field, field + '\n\tprivate long lastRenderBreakdownLog = 0L;', 1)

start = text.index('\tpublic final void renderView() {')
end = text.index('\n\tpublic void drawGameImage() {', start)

replacement = '''\tpublic final void renderView() {
\t\tlong renderViewStart = System.nanoTime();

\t\tlong stageStart = System.nanoTime();
\t\tfor (Chunk chunk : chunks) {
\t\t\tchunk.processAnimableObjects();
\t\t}
\t\tdouble animablesMs = (System.nanoTime() - stageStart) / 1_000_000.0;

\t\tstageStart = System.nanoTime();
\t\tint i = cameraRoll;
\t\tint k = cameraYaw + anInt896 & 0x7ff;
\t\tmethod144(600 + i * 3, i, k);
\t\tint currentPlane = method120();
\t\tMesh.aBoolean1684 = true;
\t\tMesh.mouseX = mouseEventX;
\t\tMesh.mouseY = mouseEventY;
\t\tgameImageBuffer.initializeRasterizer();
\t\tGameRasterizer.getInstance().reset();
\t\tif (cameraMoved) {
\t\t\tif (Options.showCamera.get()) {
\t\t\t\tSceneGraph.minimapUpdate = true;
\t\t\t}
\t\t\tChunk current = this.getCurrentChunk();
\t\t\tif (current != this.lastChunk) {
\t\t\t\tlastChunk = current;
\t\t\t\tSceneGraph.minimapUpdate = true;
\t\t\t}
\t\t\tcameraMoved = false;
\t\t}
\t\tMesh.resourceCount = 0;
\t\tdouble setupMs = (System.nanoTime() - stageStart) / 1_000_000.0;

\t\tstageStart = System.nanoTime();
\t\tfor (Chunk chunk : chunks) {
\t\t\ttry {
\t\t\t\tsceneGraph.setChunk(chunk);
\t\t\t\tsceneGraph.renderScene(xCameraPos, yCameraPos, xCameraCurve, zCameraPos, currentPlane, yCameraCurve);
\t\t\t} catch (Exception ex) {
\t\t\t\tex.printStackTrace();
\t\t\t}
\t\t}
\t\tdouble chunksMs = (System.nanoTime() - stageStart) / 1_000_000.0;

\t\tstageStart = System.nanoTime();
\t\tif (Mesh.resourceCount > 0)
\t\t\thoveredUID = Mesh.resourceIDTag[Mesh.resourceCount - 1];
\t\telse
\t\t\thoveredUID = null;
\t\tfor (Runnable r : Lists.newArrayList(SceneGraph.onCycleEnd)) {
\t\t\tif (r != null) {
\t\t\t\ttry {
\t\t\t\t\tr.run();
\t\t\t\t} catch (Exception ex) {
\t\t\t\t\tex.printStackTrace();
\t\t\t\t}
\t\t\t}
\t\t\tSceneGraph.onCycleEnd.remove(r);
\t\t}
\t\tdouble cycleEndMs = (System.nanoTime() - stageStart) / 1_000_000.0;

\t\tstageStart = System.nanoTime();
\t\tsceneGraph.cleanUpShortLivedObjects();
\t\tdouble cleanupMs = (System.nanoTime() - stageStart) / 1_000_000.0;

\t\tstageStart = System.nanoTime();
\t\tdrawDebugOverlay();
\t\tdouble debugMs = (System.nanoTime() - stageStart) / 1_000_000.0;

\t\tstageStart = System.nanoTime();
\t\tgameImageBuffer.finalize();
\t\tdouble finalizeMs = (System.nanoTime() - stageStart) / 1_000_000.0;

\t\tdouble totalMs = (System.nanoTime() - renderViewStart) / 1_000_000.0;
\t\tlong now = System.currentTimeMillis();
\t\tif (now - lastRenderBreakdownLog >= 1000L) {
\t\t\tlastRenderBreakdownLog = now;
\t\t\tSystem.out.printf("[RENDER] total=%.2f ms | anim=%.2f | setup=%.2f | chunks=%.2f | cycle=%.2f | cleanup=%.2f | debug=%.2f | finalize=%.2f | loadedChunks=%d%n",
\t\t\t\ttotalMs, animablesMs, setupMs, chunksMs, cycleEndMs, cleanupMs, debugMs, finalizeMs, chunks.size());
\t\t}
\t}
'''

text = text[:start] + replacement + text[end:]
path.write_text(text)
