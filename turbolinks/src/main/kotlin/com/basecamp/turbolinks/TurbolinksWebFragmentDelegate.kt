package com.basecamp.turbolinks

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebView
import kotlin.random.Random

@Suppress("unused")
open class TurbolinksWebFragmentDelegate(val fragment: TurbolinksWebFragment,
                                         val callback: TurbolinksWebFragmentCallback,
                                         val navigator: TurbolinksNavigator) : TurbolinksSessionCallback {

    private var location = fragment.location
    private val identifier = generateIdentifier()
    private var isInitialVisit = true
    private var isWebViewAttachedToNewDestination = false
    private var screenshot: Bitmap? = null
    private var screenshotOrientation = 0
    private val turbolinksView: TurbolinksView?
        get() = callback.onProvideTurbolinksView()
    private val turbolinksErrorPlaceholder: ViewGroup?
        get() = callback.onProvideErrorPlaceholder()

    val webView: WebView?
        get() = session().webView

    init {
        navigator.onNavigationVisit = { onReady ->
            detachWebView(onReady)
        }
    }

    fun onStart() {
        initNavigationVisit()
    }

    fun session(): TurbolinksSession {
        return fragment.session
    }

    // -----------------------------------------------------------------------
    // TurbolinksSessionCallback interface
    // -----------------------------------------------------------------------

    override fun onPageStarted(location: String) {
        callback.onColdBootPageStarted(location)
    }

    override fun onPageFinished(location: String) {
        callback.onColdBootPageCompleted(location)
    }

    override fun pageInvalidated() {}

    override fun visitLocationStarted(location: String) {
        callback.onVisitStarted(location)

        if (isWebViewAttachedToNewDestination) {
            showProgressView(location)
        }
    }

    override fun visitRendered() {
        fragment.pageViewModel.setTitle(title())
        removeTransitionalViews()
    }

    override fun visitCompleted() {
        callback.onVisitCompleted(location)
        fragment.pageViewModel.setTitle(title())
        removeTransitionalViews()
    }

    override fun onReceivedError(errorCode: Int) {
        handleError(errorCode)
        removeTransitionalViews()
    }

    override fun requestFailedWithStatusCode(statusCode: Int) {
        handleError(statusCode)
        removeTransitionalViews()
    }

    override fun visitProposedToLocation(location: String, action: String,
                                         properties: PathProperties) {
        val navigated = navigator.navigate(location, action, properties)

        // In the case of a NONE presentation, reload the page with fresh data
        if (!navigated) {
            visit(location, restoreWithCachedSnapshot = false, reload = false)
        }
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private fun initNavigationVisit() {
        val navigated = fragment.sharedViewModel.modalResult?.let {
            navigator.navigate(it.location, it.action)
        } ?: false

        if (!navigated) {
            initView()
            attachWebViewAndVisit()
        }
    }

    private fun initView() {
        callback.onUpdateView()
        turbolinksView?.apply {
            initializePullToRefresh(this)
            showScreenshotIfAvailable(this)
            screenshot = null
            screenshotOrientation = 0
        }
    }

    private fun attachWebView(): Boolean {
        val view = turbolinksView ?: return false
        return view.attachWebView(requireNotNull(webView)).also {
            if (it) callback.onWebViewAttached()
        }
    }

    /**
     * It's necessary to detach the shared WebView from a screen *before* it is hidden or exits and
     * the navigation animations run. The framework animator expects that the View hierarchy will
     * not change during the transition. Because the incoming screen will attach the WebView to the
     * new view hierarchy, it needs to already be detached from the previous screen.
     */
    private fun detachWebView(onReady: () -> Unit = {}) {
        val view = webView ?: return
        screenshotView()

        // Clear the current toolbar title to prevent buggy animation
        // effect when transitioning to the next/previous screen.
        fragment.onProvideToolbar()?.title = ""

        turbolinksView?.detachWebView(view)
        turbolinksView?.post { onReady() }
        callback.onWebViewDetached()
    }

    private fun attachWebViewAndVisit() {
        // Attempt to attach the WebView. It may already be attached to the current instance.
        isWebViewAttachedToNewDestination = attachWebView()

        // Visit every time the WebView is reattached to the current Fragment.
        if (isWebViewAttachedToNewDestination) {
            visit(location, restoreWithCachedSnapshot = !isInitialVisit, reload = false)
            isInitialVisit = false
        }
    }

    private fun title(): String {
        return webView?.title ?: ""
    }

    private fun visit(location: String, restoreWithCachedSnapshot: Boolean, reload: Boolean) {
        session().visit(TurbolinksVisit(
                location = location,
                destinationIdentifier = identifier,
                restoreWithCachedSnapshot = restoreWithCachedSnapshot,
                reload = reload,
                callback = this
        ))
    }

    private fun screenshotView() {
        if (!session().enableScreenshots) return

        turbolinksView?.let {
            screenshot = it.createScreenshot()
            screenshotOrientation = it.screenshotOrientation()
            showScreenshotIfAvailable(it)
        }
    }

    private fun showProgressView(location: String) {
        val progressView = callback.createProgressView(location)
        turbolinksView?.addProgressView(progressView)
    }

    private fun initializePullToRefresh(turbolinksView: TurbolinksView) {
        turbolinksView.refreshLayout.apply {
            isEnabled = callback.shouldEnablePullToRefresh()
            setOnRefreshListener {
                isWebViewAttachedToNewDestination = false
                visit(location, restoreWithCachedSnapshot = false, reload = true)
            }
        }
    }

    private fun showScreenshotIfAvailable(turbolinksView: TurbolinksView) {
        if (screenshotOrientation == turbolinksView.screenshotOrientation()) {
            screenshot?.let { turbolinksView.addScreenshot(it) }
        }
    }

    private fun removeTransitionalViews() {
        turbolinksView?.refreshLayout?.isRefreshing = false

        // TODO: This delay shouldn't be necessary, but visitRendered() is being called early.
        delay(200) {
            turbolinksView?.removeProgressView()
            turbolinksView?.removeScreenshot()
        }
    }

    private fun handleError(code: Int) {
        val errorView = callback.createErrorView(code)

        // Make sure the underlying WebView isn't clickable.
        errorView.isClickable = true

        turbolinksErrorPlaceholder?.removeAllViews()
        turbolinksErrorPlaceholder?.addView(errorView)
    }

    private fun generateIdentifier(): Int {
        return Random.nextInt(0, 999999999)
    }

    private fun logEvent(event: String, vararg params: Pair<String, Any>) {
        logEvent(event, params.toList())
    }
}