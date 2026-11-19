// Fixture for `semgrep --test`: proves each dead-button rule fires on the bad
// shape and stays quiet on the good one. Not compiled into the app.

@Composable
fun Bad() {
    // ruleid: empty-click-handler
    Button(onClick = { }) { Text("Save") }
    // ruleid: empty-click-handler
    SecondaryButton("Zoom", onClick = {})
    // ruleid: empty-click-handler
    Box(Modifier.clickable { })
    // ruleid: click-handler-is-todo
    PrimaryButton("Cast") { TODO("cast") }
}

@Composable
fun Good(viewModel: VM) {
    // ok: empty-click-handler
    Button(onClick = { viewModel.save() }) { Text("Save") }
    // ok: empty-click-handler
    Box(Modifier.clickable { viewModel.open() })
}

class Engine : PlayerEngine {
    // ruleid: setter-only-records-state
    override fun setResizeMode(mode: ResizeMode) {
        _state.update { it.copy(resizeMode = mode) }
    }

    // ok: setter-only-records-state
    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
        _state.update { it.copy(playbackSpeed = speed) }
    }
}
