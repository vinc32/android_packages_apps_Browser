/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.browser;

import com.android.browser.ScrollWebView.ScrollListener;
import com.android.browser.search.SearchEngine;
import com.android.common.Search;
import com.android.common.speech.LoggingEvents;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.WebAddress;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Browser;
import android.provider.BrowserContract;
import android.provider.ContactsContract;
import android.provider.BrowserContract.Images;
import android.provider.ContactsContract.Intents.Insert;
import android.provider.Downloads;
import android.provider.MediaStore;
import android.speech.RecognizerResultsIntent;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Patterns;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.DownloadListener;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebIconDatabase;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BrowserActivity extends Activity
    implements View.OnCreateContextMenuListener, DownloadListener,
    PopupMenu.OnMenuItemClickListener {

    /* Define some aliases to make these debugging flags easier to refer to.
     * This file imports android.provider.Browser, so we can't just refer to "Browser.DEBUG".
     */
    private final static boolean DEBUG = com.android.browser.Browser.DEBUG;
    private final static boolean LOGV_ENABLED = com.android.browser.Browser.LOGV_ENABLED;
    private final static boolean LOGD_ENABLED = com.android.browser.Browser.LOGD_ENABLED;

    // These are single-character shortcuts for searching popular sources.
    private static final int SHORTCUT_INVALID = 0;
    private static final int SHORTCUT_GOOGLE_SEARCH = 1;
    private static final int SHORTCUT_WIKIPEDIA_SEARCH = 2;
    private static final int SHORTCUT_DICTIONARY_SEARCH = 3;
    private static final int SHORTCUT_GOOGLE_MOBILE_LOCAL_SEARCH = 4;

    private static class ClearThumbnails extends AsyncTask<File, Void, Void> {
        @Override
        public Void doInBackground(File... files) {
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) {
                      Log.e(LOGTAG, f.getPath() + " was not deleted");
                    }
                }
            }
            return null;
        }
    }

    /**
     * This layout holds everything you see below the status bar, including the
     * error console, the custom view container, and the webviews.
     */
    private FrameLayout mBrowserFrameLayout;

    private boolean mXLargeScreenSize;

    private Boolean mIsProviderPresent = null;
    private Uri mRlzUri = null;

    @Override
    public void onCreate(Bundle icicle) {
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, this + " onStart");
        }
        super.onCreate(icicle);
        // test the browser in OpenGL
        // requestWindowFeature(Window.FEATURE_OPENGL);

        // enable this to test the browser in 32bit
        if (false) {
            getWindow().setFormat(PixelFormat.RGBX_8888);
            BitmapFactory.setDefaultConfig(Bitmap.Config.ARGB_8888);
        }

        if (AccessibilityManager.getInstance(this).isEnabled()) {
            setDefaultKeyMode(DEFAULT_KEYS_DISABLE);
        } else {
            setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);
        }

        mResolver = getContentResolver();

        // Keep a settings instance handy.
        mSettings = BrowserSettings.getInstance();

        // If this was a web search request, pass it on to the default web
        // search provider and finish this activity.
        if (handleWebSearchIntent(getIntent())) {
            finish();
            return;
        }

        mSecLockIcon = Resources.getSystem().getDrawable(
                android.R.drawable.ic_secure);
        mMixLockIcon = Resources.getSystem().getDrawable(
                android.R.drawable.ic_partial_secure);

        // Create the tab control and our initial tab
        mTabControl = new TabControl(this);

        mXLargeScreenSize = (getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK)
                == Configuration.SCREENLAYOUT_SIZE_XLARGE;

        FrameLayout frameLayout = (FrameLayout) getWindow().getDecorView()
                .findViewById(com.android.internal.R.id.content);
        mBrowserFrameLayout = (FrameLayout) LayoutInflater.from(this)
                .inflate(R.layout.custom_screen, null);
        mContentView = (FrameLayout) mBrowserFrameLayout.findViewById(
                R.id.main_content);
        mErrorConsoleContainer = (LinearLayout) mBrowserFrameLayout
                .findViewById(R.id.error_console);
        mCustomViewContainer = (FrameLayout) mBrowserFrameLayout
                .findViewById(R.id.fullscreen_custom_content);
        frameLayout.addView(mBrowserFrameLayout, COVER_SCREEN_PARAMS);

        if (mXLargeScreenSize) {
            mTitleBar = new TitleBarXLarge(this);
            mTitleBar.setProgress(100);
            mFakeTitleBar = new TitleBarXLarge(this);
            ActionBar actionBar = getActionBar();
            mTabBar = new TabBar(this, mTabControl, (TitleBarXLarge) mFakeTitleBar);
            actionBar.setCustomNavigationMode(mTabBar);
            // disable built in zoom controls
            mTabControl.setDisplayZoomControls(false);
        } else {
            mTitleBar = new TitleBar(this);
            // mTitleBar will be always be shown in the fully loaded mode on
            // phone
            mTitleBar.setProgress(100);
            mFakeTitleBar = new TitleBar(this);
        }

        // Open the icon database and retain all the bookmark urls for favicons
        retainIconsOnStartup();

        mSettings.setTabControl(mTabControl);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Browser");

        // Find out if the network is currently up.
        ConnectivityManager cm = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        if (info != null) {
            mIsNetworkUp = info.isAvailable();
        }

        /* enables registration for changes in network status from
           http stack */
        mNetworkStateChangedFilter = new IntentFilter();
        mNetworkStateChangedFilter.addAction(
                ConnectivityManager.CONNECTIVITY_ACTION);
        mNetworkStateIntentReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction().equals(
                            ConnectivityManager.CONNECTIVITY_ACTION)) {

                        NetworkInfo info = intent.getParcelableExtra(
                                ConnectivityManager.EXTRA_NETWORK_INFO);
                        String typeName = info.getTypeName();
                        String subtypeName = info.getSubtypeName();
                        sendNetworkType(typeName.toLowerCase(),
                                (subtypeName != null ? subtypeName.toLowerCase() : ""));

                        onNetworkToggle(info.isAvailable());
                    }
                }
            };

        // Unless the last browser usage was within 24 hours, destroy any
        // remaining incognito tabs.

        Calendar lastActiveDate = icicle != null ? (Calendar) icicle.getSerializable("lastActiveDate") : null;
        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DATE, -1);

        boolean dontRestoreIncognitoTabs = lastActiveDate == null
            || lastActiveDate.before(yesterday)
            || lastActiveDate.after(today);

        if (!mTabControl.restoreState(icicle, dontRestoreIncognitoTabs)) {
            // clear up the thumbnail directory if we can't restore the state as
            // none of the files in the directory are referenced any more.
            new ClearThumbnails().execute(
                    mTabControl.getThumbnailDir().listFiles());
            // there is no quit on Android. But if we can't restore the state,
            // we can treat it as a new Browser, remove the old session cookies.
            CookieManager.getInstance().removeSessionCookie();
            // remove any incognito files
            WebView.cleanupPrivateBrowsingFiles(this);
            final Intent intent = getIntent();
            final Bundle extra = intent.getExtras();
            // Create an initial tab.
            // If the intent is ACTION_VIEW and data is not null, the Browser is
            // invoked to view the content by another application. In this case,
            // the tab will be close when exit.
            UrlData urlData = getUrlDataFromIntent(intent);

            String action = intent.getAction();
            final Tab t = mTabControl.createNewTab(
                    (Intent.ACTION_VIEW.equals(action) &&
                    intent.getData() != null)
                    || RecognizerResultsIntent.ACTION_VOICE_SEARCH_RESULTS
                    .equals(action),
                    intent.getStringExtra(Browser.EXTRA_APPLICATION_ID),
                    urlData.mUrl, false);
            mTabControl.setCurrentTab(t);
            attachTabToContentView(t);
            WebView webView = t.getWebView();
            if (extra != null) {
                int scale = extra.getInt(Browser.INITIAL_ZOOM_LEVEL, 0);
                if (scale > 0 && scale <= 1000) {
                    webView.setInitialScale(scale);
                }
            }

            if (urlData.isEmpty()) {
                loadUrl(webView, mSettings.getHomePage());
            } else {
                loadUrlDataIn(t, urlData);
            }
        } else {
            if (dontRestoreIncognitoTabs) {
                WebView.cleanupPrivateBrowsingFiles(this);
            }

            // TabControl.restoreState() will create a new tab even if
            // restoring the state fails.
            attachTabToContentView(mTabControl.getCurrentTab());
        }

        // Delete old thumbnails to save space
        File dir = mTabControl.getThumbnailDir();
        if (dir.exists()) {
            for (String child : dir.list()) {
                File f = new File(dir, child);
                f.delete();
            }
        }

        // Read JavaScript flags if it exists.
        String jsFlags = mSettings.getJsFlags();
        if (jsFlags.trim().length() != 0) {
            mTabControl.getCurrentWebView().setJsFlags(jsFlags);
        }

        // Start watching the default geolocation permissions
        mSystemAllowGeolocationOrigins
                = new SystemAllowGeolocationOrigins(getApplicationContext());
        mSystemAllowGeolocationOrigins.start();
    }

    ScrollListener getScrollListener() {
        return mTabBar;
    }

    /**
     * Feed the previously stored results strings to the BrowserProvider so that
     * the SearchDialog will show them instead of the standard searches.
     * @param result String to show on the editable line of the SearchDialog.
     */
    /* package */ void showVoiceSearchResults(String result) {
        ContentProviderClient client = mResolver.acquireContentProviderClient(
                Browser.BOOKMARKS_URI);
        ContentProvider prov = client.getLocalContentProvider();
        BrowserProvider bp = (BrowserProvider) prov;
        bp.setQueryResults(mTabControl.getCurrentTab().getVoiceSearchResults());
        client.release();

        Bundle bundle = createGoogleSearchSourceBundle(
                GOOGLE_SEARCH_SOURCE_SEARCHKEY);
        bundle.putBoolean(SearchManager.CONTEXT_IS_VOICE, true);
        startSearch(result, false, bundle, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Tab current = mTabControl.getCurrentTab();
        // When a tab is closed on exit, the current tab index is set to -1.
        // Reset before proceed as Browser requires the current tab to be set.
        if (current == null) {
            // Try to reset the tab in case the index was incorrect.
            current = mTabControl.getTab(0);
            if (current == null) {
                // No tabs at all so just ignore this intent.
                return;
            }
            mTabControl.setCurrentTab(current);
            attachTabToContentView(current);
            resetTitleAndIcon(current.getWebView());
        }
        final String action = intent.getAction();
        final int flags = intent.getFlags();
        if (Intent.ACTION_MAIN.equals(action) ||
                (flags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            // just resume the browser
            return;
        }
        // In case the SearchDialog is open.
        ((SearchManager) getSystemService(Context.SEARCH_SERVICE))
                .stopSearch();
        boolean activateVoiceSearch = RecognizerResultsIntent
                .ACTION_VOICE_SEARCH_RESULTS.equals(action);
        if (Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_SEARCH.equals(action)
                || MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(action)
                || Intent.ACTION_WEB_SEARCH.equals(action)
                || activateVoiceSearch) {
            if (current.isInVoiceSearchMode()) {
                String title = current.getVoiceDisplayTitle();
                if (title != null && title.equals(intent.getStringExtra(
                        SearchManager.QUERY))) {
                    // The user submitted the same search as the last voice
                    // search, so do nothing.
                    return;
                }
                if (Intent.ACTION_SEARCH.equals(action)
                        && current.voiceSearchSourceIsGoogle()) {
                    Intent logIntent = new Intent(
                            LoggingEvents.ACTION_LOG_EVENT);
                    logIntent.putExtra(LoggingEvents.EXTRA_EVENT,
                            LoggingEvents.VoiceSearch.QUERY_UPDATED);
                    logIntent.putExtra(
                            LoggingEvents.VoiceSearch.EXTRA_QUERY_UPDATED_VALUE,
                            intent.getDataString());
                    sendBroadcast(logIntent);
                    // Note, onPageStarted will revert the voice title bar
                    // When http://b/issue?id=2379215 is fixed, we should update
                    // the title bar here.
                }
            }
            // If this was a search request (e.g. search query directly typed into the address bar),
            // pass it on to the default web search provider.
            if (handleWebSearchIntent(intent)) {
                return;
            }

            UrlData urlData = getUrlDataFromIntent(intent);
            if (urlData.isEmpty()) {
                urlData = new UrlData(mSettings.getHomePage());
            }

            final String appId = intent
                    .getStringExtra(Browser.EXTRA_APPLICATION_ID);
            if ((Intent.ACTION_VIEW.equals(action)
                    // If a voice search has no appId, it means that it came
                    // from the browser.  In that case, reuse the current tab.
                    || (activateVoiceSearch && appId != null))
                    && !getPackageName().equals(appId)
                    && (flags & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0) {
                Tab appTab = mTabControl.getTabFromId(appId);
                if (appTab != null) {
                    Log.i(LOGTAG, "Reusing tab for " + appId);
                    // Dismiss the subwindow if applicable.
                    dismissSubWindow(appTab);
                    // Since we might kill the WebView, remove it from the
                    // content view first.
                    removeTabFromContentView(appTab);
                    // Recreate the main WebView after destroying the old one.
                    // If the WebView has the same original url and is on that
                    // page, it can be reused.
                    boolean needsLoad =
                            mTabControl.recreateWebView(appTab, urlData);

                    if (current != appTab) {
                        switchToTab(mTabControl.getTabIndex(appTab));
                        if (needsLoad) {
                            loadUrlDataIn(appTab, urlData);
                        }
                    } else {
                        // If the tab was the current tab, we have to attach
                        // it to the view system again.
                        attachTabToContentView(appTab);
                        if (needsLoad) {
                            loadUrlDataIn(appTab, urlData);
                        }
                    }
                    return;
                } else {
                    // No matching application tab, try to find a regular tab
                    // with a matching url.
                    appTab = mTabControl.findUnusedTabWithUrl(urlData.mUrl);
                    if (appTab != null) {
                        if (current != appTab) {
                            switchToTab(mTabControl.getTabIndex(appTab));
                        }
                        // Otherwise, we are already viewing the correct tab.
                    } else {
                        // if FLAG_ACTIVITY_BROUGHT_TO_FRONT flag is on, the url
                        // will be opened in a new tab unless we have reached
                        // MAX_TABS. Then the url will be opened in the current
                        // tab. If a new tab is created, it will have "true" for
                        // exit on close.
                        openTabAndShow(urlData, true, appId);
                    }
                }
            } else {
                if (!urlData.isEmpty()
                        && urlData.mUrl.startsWith("about:debug")) {
                    if ("about:debug.dom".equals(urlData.mUrl)) {
                        current.getWebView().dumpDomTree(false);
                    } else if ("about:debug.dom.file".equals(urlData.mUrl)) {
                        current.getWebView().dumpDomTree(true);
                    } else if ("about:debug.render".equals(urlData.mUrl)) {
                        current.getWebView().dumpRenderTree(false);
                    } else if ("about:debug.render.file".equals(urlData.mUrl)) {
                        current.getWebView().dumpRenderTree(true);
                    } else if ("about:debug.display".equals(urlData.mUrl)) {
                        current.getWebView().dumpDisplayTree();
                    } else if (urlData.mUrl.startsWith("about:debug.drag")) {
                        int index = urlData.mUrl.codePointAt(16) - '0';
                        if (index <= 0 || index > 9) {
                            current.getWebView().setDragTracker(null);
                        } else {
                            current.getWebView().setDragTracker(new MeshTracker(index));
                        }
                    } else {
                        mSettings.toggleDebugSettings();
                    }
                    return;
                }
                // Get rid of the subwindow if it exists
                dismissSubWindow(current);
                // If the current Tab is being used as an application tab,
                // remove the association, since the new Intent means that it is
                // no longer associated with that application.
                current.setAppId(null);
                loadUrlDataIn(current, urlData);
            }
        }
    }

    private int parseUrlShortcut(String url) {
        if (url == null) return SHORTCUT_INVALID;

        // FIXME: quick search, need to be customized by setting
        if (url.length() > 2 && url.charAt(1) == ' ') {
            switch (url.charAt(0)) {
            case 'g': return SHORTCUT_GOOGLE_SEARCH;
            case 'w': return SHORTCUT_WIKIPEDIA_SEARCH;
            case 'd': return SHORTCUT_DICTIONARY_SEARCH;
            case 'l': return SHORTCUT_GOOGLE_MOBILE_LOCAL_SEARCH;
            }
        }
        return SHORTCUT_INVALID;
    }

    /**
     * Launches the default web search activity with the query parameters if the given intent's data
     * are identified as plain search terms and not URLs/shortcuts.
     * @return true if the intent was handled and web search activity was launched, false if not.
     */
    private boolean handleWebSearchIntent(Intent intent) {
        if (intent == null) return false;

        String url = null;
        final String action = intent.getAction();
        if (RecognizerResultsIntent.ACTION_VOICE_SEARCH_RESULTS.equals(
                action)) {
            return false;
        }
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) url = data.toString();
        } else if (Intent.ACTION_SEARCH.equals(action)
                || MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(action)
                || Intent.ACTION_WEB_SEARCH.equals(action)) {
            url = intent.getStringExtra(SearchManager.QUERY);
        }
        return handleWebSearchRequest(url, intent.getBundleExtra(SearchManager.APP_DATA),
                intent.getStringExtra(SearchManager.EXTRA_DATA_KEY));
    }

    /**
     * Launches the default web search activity with the query parameters if the given url string
     * was identified as plain search terms and not URL/shortcut.
     * @return true if the request was handled and web search activity was launched, false if not.
     */
    private boolean handleWebSearchRequest(String inUrl, Bundle appData, String extraData) {
        if (inUrl == null) return false;

        // In general, we shouldn't modify URL from Intent.
        // But currently, we get the user-typed URL from search box as well.
        String url = fixUrl(inUrl).trim();

        // URLs and site specific search shortcuts are handled by the regular flow of control, so
        // return early.
        if (Patterns.WEB_URL.matcher(url).matches()
                || ACCEPTED_URI_SCHEMA.matcher(url).matches()
                || parseUrlShortcut(url) != SHORTCUT_INVALID) {
            return false;
        }

        final ContentResolver cr = mResolver;
        final String newUrl = url;
        if (mTabControl == null || !mTabControl.getCurrentWebView().isPrivateBrowsingEnabled()) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... unused) {
                        Browser.updateVisitedHistory(cr, newUrl, false);
                        Browser.addSearchUrl(cr, newUrl);
                    return null;
                }
            }.execute();
        }

        SearchEngine searchEngine = mSettings.getSearchEngine();
        if (searchEngine == null) return false;
        searchEngine.startSearch(this, url, appData, extraData);

        return true;
    }

    private UrlData getUrlDataFromIntent(Intent intent) {
        String url = "";
        Map<String, String> headers = null;
        if (intent != null) {
            final String action = intent.getAction();
            if (Intent.ACTION_VIEW.equals(action)) {
                url = smartUrlFilter(intent.getData());
                if (url != null && url.startsWith("content:")) {
                    /* Append mimetype so webview knows how to display */
                    String mimeType = intent.resolveType(getContentResolver());
                    if (mimeType != null) {
                        url += "?" + mimeType;
                    }
                }
                if (url != null && url.startsWith("http")) {
                    final Bundle pairs = intent
                            .getBundleExtra(Browser.EXTRA_HEADERS);
                    if (pairs != null && !pairs.isEmpty()) {
                        Iterator<String> iter = pairs.keySet().iterator();
                        headers = new HashMap<String, String>();
                        while (iter.hasNext()) {
                            String key = iter.next();
                            headers.put(key, pairs.getString(key));
                        }
                    }
                }
            } else if (Intent.ACTION_SEARCH.equals(action)
                    || MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(action)
                    || Intent.ACTION_WEB_SEARCH.equals(action)) {
                url = intent.getStringExtra(SearchManager.QUERY);
                if (url != null) {
                    mLastEnteredUrl = url;
                    // In general, we shouldn't modify URL from Intent.
                    // But currently, we get the user-typed URL from search box as well.
                    url = fixUrl(url);
                    url = smartUrlFilter(url);
                    final ContentResolver cr = mResolver;
                    final String newUrl = url;
                    if (mTabControl == null
                            || mTabControl.getCurrentWebView() == null
                            || !mTabControl.getCurrentWebView().isPrivateBrowsingEnabled()) {
                        new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected Void doInBackground(Void... unused) {
                                Browser.updateVisitedHistory(cr, newUrl, false);
                                return null;
                            }
                        }.execute();
                    }
                    String searchSource = "&source=android-" + GOOGLE_SEARCH_SOURCE_SUGGEST + "&";
                    if (url.contains(searchSource)) {
                        String source = null;
                        final Bundle appData = intent.getBundleExtra(SearchManager.APP_DATA);
                        if (appData != null) {
                            source = appData.getString(Search.SOURCE);
                        }
                        if (TextUtils.isEmpty(source)) {
                            source = GOOGLE_SEARCH_SOURCE_UNKNOWN;
                        }
                        url = url.replace(searchSource, "&source=android-"+source+"&");
                    }
                }
            }
        }
        return new UrlData(url, headers, intent);
    }
    /* package */ void showVoiceTitleBar(String title) {
        mTitleBar.setInVoiceMode(true);
        mTitleBar.setDisplayTitle(title);
        mFakeTitleBar.setInVoiceMode(true);
        mFakeTitleBar.setDisplayTitle(title);
    }
    /* package */ void revertVoiceTitleBar() {
        mTitleBar.setInVoiceMode(false);
        mTitleBar.setDisplayTitle(mUrl);
        mFakeTitleBar.setInVoiceMode(false);
        mFakeTitleBar.setDisplayTitle(mUrl);
    }
    /* package */ static String fixUrl(String inUrl) {
        // FIXME: Converting the url to lower case
        // duplicates functionality in smartUrlFilter().
        // However, changing all current callers of fixUrl to
        // call smartUrlFilter in addition may have unwanted
        // consequences, and is deferred for now.
        int colon = inUrl.indexOf(':');
        boolean allLower = true;
        for (int index = 0; index < colon; index++) {
            char ch = inUrl.charAt(index);
            if (!Character.isLetter(ch)) {
                break;
            }
            allLower &= Character.isLowerCase(ch);
            if (index == colon - 1 && !allLower) {
                inUrl = inUrl.substring(0, colon).toLowerCase()
                        + inUrl.substring(colon);
            }
        }
        if (inUrl.startsWith("http://") || inUrl.startsWith("https://"))
            return inUrl;
        if (inUrl.startsWith("http:") ||
                inUrl.startsWith("https:")) {
            if (inUrl.startsWith("http:/") || inUrl.startsWith("https:/")) {
                inUrl = inUrl.replaceFirst("/", "//");
            } else inUrl = inUrl.replaceFirst(":", "://");
        }
        return inUrl;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, "BrowserActivity.onResume: this=" + this);
        }

        if (!mActivityInPause) {
            Log.e(LOGTAG, "BrowserActivity is already resumed.");
            return;
        }

        mTabControl.resumeCurrentTab();
        mActivityInPause = false;
        resumeWebViewTimers();

        if (mWakeLock.isHeld()) {
            mHandler.removeMessages(RELEASE_WAKELOCK);
            mWakeLock.release();
        }

        registerReceiver(mNetworkStateIntentReceiver,
                         mNetworkStateChangedFilter);
        WebView.enablePlatformNotifications();
    }

    /**
     * Since the actual title bar is embedded in the WebView, and removing it
     * would change its appearance, use a different TitleBar to show overlayed
     * at the top of the screen, when the menu is open or the page is loading.
     */
    private TitleBarBase mFakeTitleBar;

    /**
     * Keeps track of whether the options menu is open.  This is important in
     * determining whether to show or hide the title bar overlay.
     */
    private boolean mOptionsMenuOpen;

    /**
     * Only meaningful when mOptionsMenuOpen is true.  This variable keeps track
     * of whether the configuration has changed.  The first onMenuOpened call
     * after a configuration change is simply a reopening of the same menu
     * (i.e. mIconView did not change).
     */
    private boolean mConfigChanged;

    /**
     * Whether or not the options menu is in its smaller, icon menu form.  When
     * true, we want the title bar overlay to be up.  When false, we do not.
     * Only meaningful if mOptionsMenuOpen is true.
     */
    private boolean mIconView;

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (Window.FEATURE_OPTIONS_PANEL == featureId) {
            if (mOptionsMenuOpen) {
                if (mConfigChanged) {
                    // We do not need to make any changes to the state of the
                    // title bar, since the only thing that happened was a
                    // change in orientation
                    mConfigChanged = false;
                } else {
                    if (mIconView) {
                        // Switching the menu to expanded view, so hide the
                        // title bar.
                        hideFakeTitleBar();
                        mIconView = false;
                    } else {
                        // Switching the menu back to icon view, so show the
                        // title bar once again.
                        showFakeTitleBar();
                        mIconView = true;
                    }
                }
            } else {
                // The options menu is closed, so open it, and show the title
                showFakeTitleBar();
                mOptionsMenuOpen = true;
                mConfigChanged = false;
                mIconView = true;
            }
        }
        return true;
    }

    void showFakeTitleBar() {
        if (!isFakeTitleBarShowing() && mActiveTabsPage == null && !mActivityInPause) {
            WebView mainView = mTabControl.getCurrentWebView();
            // if there is no current WebView, don't show the faked title bar;
            if (mainView == null) {
                return;
            }
            // Do not need to check for null, since the current tab will have
            // at least a main WebView, or we would have returned above.
            if (isInCustomActionMode()) {
                // Do not show the fake title bar, while a custom ActionMode
                // (i.e. find or select) is showing.
                return;
            }
            if (mXLargeScreenSize) {
                mContentView.addView(mFakeTitleBar);
                mTabBar.onShowTitleBar();
            } else {
                WindowManager manager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

                // Add the title bar to the window manager so it can receive
                // touches
                // while the menu is up
                WindowManager.LayoutParams params =
                        new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.TYPE_APPLICATION,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                                PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP;
                boolean atTop = mainView.getScrollY() == 0;
                params.windowAnimations = atTop ? 0 : R.style.TitleBar;
                manager.addView(mFakeTitleBar, params);
            }
        }
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        mOptionsMenuOpen = false;
        if (!mInLoad) {
            hideFakeTitleBar();
        } else if (!mIconView) {
            // The page is currently loading, and we are in expanded mode, so
            // we were not showing the menu.  Show it once again.  It will be
            // removed when the page finishes.
            showFakeTitleBar();
        }
    }

    void stopScrolling() {
        ((ScrollWebView) mTabControl.getCurrentWebView()).stopScroll();
    }

    void hideFakeTitleBar() {
        if (!isFakeTitleBarShowing()) return;
        if (mXLargeScreenSize) {
            mContentView.removeView(mFakeTitleBar);
            mTabBar.onHideTitleBar();
        } else {
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) mFakeTitleBar.getLayoutParams();
            WebView mainView = mTabControl.getCurrentWebView();
            // Although we decided whether or not to animate based on the
            // current
            // scroll position, the scroll position may have changed since the
            // fake title bar was displayed. Make sure it has the appropriate
            // animation/lack thereof before removing.
            params.windowAnimations =
                    mainView != null && mainView.getScrollY() == 0 ? 0 : R.style.TitleBar;
            WindowManager manager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            manager.updateViewLayout(mFakeTitleBar, params);
            manager.removeView(mFakeTitleBar);
        }
    }

    boolean isFakeTitleBarShowing() {
        return (mFakeTitleBar.getParent() != null);
    }

    /**
     * Special method for the fake title bar to call when displaying its context
     * menu, since it is in its own Window, and its parent does not show a
     * context menu.
     */
    /* package */ void showTitleBarContextMenu() {
        if (null == mTitleBar.getParent()) {
            return;
        }
        openContextMenu(mTitleBar);
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        if (mInLoad) {
            showFakeTitleBar();
        }
    }

    /**
     *  onSaveInstanceState(Bundle map)
     *  onSaveInstanceState is called right before onStop(). The map contains
     *  the saved state.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, "BrowserActivity.onSaveInstanceState: this=" + this);
        }
        // the default implementation requires each view to have an id. As the
        // browser handles the state itself and it doesn't use id for the views,
        // don't call the default implementation. Otherwise it will trigger the
        // warning like this, "couldn't save which view has focus because the
        // focused view XXX has no id".

        // Save all the tabs
        mTabControl.saveState(outState);

        // Save time so that we know how old incognito tabs (if any) are.
        outState.putSerializable("lastActiveDate", Calendar.getInstance());
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mActivityInPause) {
            Log.e(LOGTAG, "BrowserActivity is already paused.");
            return;
        }

        mTabControl.pauseCurrentTab();
        mActivityInPause = true;
        if (mTabControl.getCurrentIndex() >= 0 && !pauseWebViewTimers()) {
            mWakeLock.acquire();
            mHandler.sendMessageDelayed(mHandler
                    .obtainMessage(RELEASE_WAKELOCK), WAKELOCK_TIMEOUT);
        }

        // FIXME: This removes the active tabs page and resets the menu to
        // MAIN_MENU.  A better solution might be to do this work in onNewIntent
        // but then we would need to save it in onSaveInstanceState and restore
        // it in onCreate/onRestoreInstanceState
        if (mActiveTabsPage != null) {
            removeActiveTabPage(true);
        }

        cancelStopToast();

        // unregister network state listener
        unregisterReceiver(mNetworkStateIntentReceiver);
        WebView.disablePlatformNotifications();
    }

    @Override
    protected void onDestroy() {
        if (LOGV_ENABLED) {
            Log.v(LOGTAG, "BrowserActivity.onDestroy: this=" + this);
        }
        super.onDestroy();

        if (mUploadMessage != null) {
            mUploadMessage.onReceiveValue(null);
            mUploadMessage = null;
        }

        if (mTabControl == null) return;

        // Remove the fake title bar if it is there
        hideFakeTitleBar();

        // Remove the current tab and sub window
        Tab t = mTabControl.getCurrentTab();
        if (t != null) {
            dismissSubWindow(t);
            removeTabFromContentView(t);
        }
        // Destroy all the tabs
        mTabControl.destroy();
        WebIconDatabase.getInstance().close();

        // Stop watching the default geolocation permissions
        mSystemAllowGeolocationOrigins.stop();
        mSystemAllowGeolocationOrigins = null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mConfigChanged = true;
        super.onConfigurationChanged(newConfig);

        if (mPageInfoDialog != null) {
            mPageInfoDialog.dismiss();
            showPageInfo(
                mPageInfoView,
                mPageInfoFromShowSSLCertificateOnError);
        }
        if (mSSLCertificateDialog != null) {
            mSSLCertificateDialog.dismiss();
            showSSLCertificate(
                mSSLCertificateView);
        }
        if (mSSLCertificateOnErrorDialog != null) {
            mSSLCertificateOnErrorDialog.dismiss();
            showSSLCertificateOnError(
                mSSLCertificateOnErrorView,
                mSSLCertificateOnErrorHandler,
                mSSLCertificateOnErrorError);
        }
        if (mHttpAuthenticationDialog != null) {
            String title = ((TextView) mHttpAuthenticationDialog
                    .findViewById(com.android.internal.R.id.alertTitle)).getText()
                    .toString();
            String name = ((TextView) mHttpAuthenticationDialog
                    .findViewById(R.id.username_edit)).getText().toString();
            String password = ((TextView) mHttpAuthenticationDialog
                    .findViewById(R.id.password_edit)).getText().toString();
            int focusId = mHttpAuthenticationDialog.getCurrentFocus()
                    .getId();
            mHttpAuthenticationDialog.dismiss();
            showHttpAuthentication(mHttpAuthHandler, null, null, title,
                    name, password, focusId);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mTabControl.freeMemory();
    }

    private void resumeWebViewTimers() {
        Tab tab = mTabControl.getCurrentTab();
        if (tab == null) return; // monkey can trigger this
        boolean inLoad = tab.inLoad();
        if ((!mActivityInPause && !inLoad) || (mActivityInPause && inLoad)) {
            CookieSyncManager.getInstance().startSync();
            WebView w = tab.getWebView();
            if (w != null) {
                w.resumeTimers();
            }
        }
    }

    private boolean pauseWebViewTimers() {
        Tab tab = mTabControl.getCurrentTab();
        boolean inLoad = tab.inLoad();
        if (mActivityInPause && !inLoad) {
            CookieSyncManager.getInstance().stopSync();
            WebView w = mTabControl.getCurrentWebView();
            if (w != null) {
                w.pauseTimers();
            }
            return true;
        } else {
            return false;
        }
    }

    // Open the icon database and retain all the icons for visited sites.
    private void retainIconsOnStartup() {
        final WebIconDatabase db = WebIconDatabase.getInstance();
        db.open(getDir("icons", 0).getPath());
        Cursor c = null;
        try {
            c = Browser.getAllBookmarks(mResolver);
            if (c.moveToFirst()) {
                int urlIndex = c.getColumnIndex(Browser.BookmarkColumns.URL);
                do {
                    String url = c.getString(urlIndex);
                    db.retainIconForPageUrl(url);
                } while (c.moveToNext());
            }
        } catch (IllegalStateException e) {
            Log.e(LOGTAG, "retainIconsOnStartup", e);
        } finally {
            if (c!= null) c.close();
        }
    }

    // Helper method for getting the top window.
    WebView getTopWindow() {
        return mTabControl.getCurrentTopWebView();
    }

    TabControl getTabControl() {
        return mTabControl;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.browser, menu);
        mMenu = menu;
        updateInLoadMenuItems();
        return true;
    }

    /**
     * As the menu can be open when loading state changes
     * we must manually update the state of the stop/reload menu
     * item
     */
    private void updateInLoadMenuItems() {
        if (mMenu == null) {
            return;
        }
        MenuItem dest = mMenu.findItem(R.id.stop_reload_menu_id);
        MenuItem src = mInLoad ?
                mMenu.findItem(R.id.stop_menu_id):
                mMenu.findItem(R.id.reload_menu_id);
        if (src != null) {
            dest.setIcon(src.getIcon());
            dest.setTitle(src.getTitle());
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        // chording is not an issue with context menus, but we use the same
        // options selector, so set mCanChord to true so we can access them.
        mCanChord = true;
        int id = item.getItemId();
        boolean result = true;
        switch (id) {
            // For the context menu from the title bar
            case R.id.title_bar_copy_page_url:
                Tab currentTab = mTabControl.getCurrentTab();
                if (null == currentTab) {
                    result = false;
                    break;
                }
                WebView mainView = currentTab.getWebView();
                if (null == mainView) {
                    result = false;
                    break;
                }
                copy(mainView.getUrl());
                break;
            // -- Browser context menu
            case R.id.open_context_menu_id:
            case R.id.bookmark_context_menu_id:
            case R.id.save_link_context_menu_id:
            case R.id.share_link_context_menu_id:
            case R.id.copy_link_context_menu_id:
                final WebView webView = getTopWindow();
                if (null == webView) {
                    result = false;
                    break;
                }
                final HashMap hrefMap = new HashMap();
                hrefMap.put("webview", webView);
                final Message msg = mHandler.obtainMessage(
                        FOCUS_NODE_HREF, id, 0, hrefMap);
                webView.requestFocusNodeHref(msg);
                break;

            default:
                // For other context menus
                result = onOptionsItemSelected(item);
        }
        mCanChord = false;
        return result;
    }

    private Bundle createGoogleSearchSourceBundle(String source) {
        Bundle bundle = new Bundle();
        bundle.putString(Search.SOURCE, source);
        return bundle;
    }

    /* package */ void editUrl() {
        if (mOptionsMenuOpen) closeOptionsMenu();
        String url = (getTopWindow() == null) ? null : getTopWindow().getUrl();
        startSearch(mSettings.getHomePage().equals(url) ? null : url, true,
                null, false);
    }

    /**
     * Overriding this to insert a local information bundle
     */
    @Override
    public void startSearch(String initialQuery, boolean selectInitialQuery,
            Bundle appSearchData, boolean globalSearch) {
        if (appSearchData == null) {
            appSearchData = createGoogleSearchSourceBundle(GOOGLE_SEARCH_SOURCE_TYPE);
        }

        SearchEngine searchEngine = mSettings.getSearchEngine();
        if (searchEngine != null && !searchEngine.supportsVoiceSearch()) {
            appSearchData.putBoolean(SearchManager.DISABLE_VOICE_SEARCH, true);
        }

        super.startSearch(initialQuery, selectInitialQuery, appSearchData, globalSearch);
    }

    /**
     * Switch tabs.  Called by the TitleBarSet when sliding the title bar
     * results in changing tabs.
     * @param index Index of the tab to change to, as defined by
     *              mTabControl.getTabIndex(Tab t).
     * @return boolean True if we successfully switched to a different tab.  If
     *                 the indexth tab is null, or if that tab is the same as
     *                 the current one, return false.
     */
    /* package */ boolean switchToTab(int index) {
        Tab tab = mTabControl.getTab(index);
        Tab currentTab = mTabControl.getCurrentTab();
        if (tab == null || tab == currentTab) {
            return false;
        }
        if (currentTab != null) {
            // currentTab may be null if it was just removed.  In that case,
            // we do not need to remove it
            removeTabFromContentView(currentTab);
        }
        mTabControl.setCurrentTab(tab);
        attachTabToContentView(tab);
        resetTitleIconAndProgress();
        updateLockIconToLatest();
        return true;
    }

    /* package */ Tab openTabToHomePage() {
        return openTabAndShow(mSettings.getHomePage(), false, null);
    }

    /* package */ void closeCurrentWindow() {
        final Tab current = mTabControl.getCurrentTab();
        if (mTabControl.getTabCount() == 1) {
            // This is the last tab.  Open a new one, with the home
            // page and close the current one.
            openTabToHomePage();
            closeTab(current);
            return;
        }
        final Tab parent = current.getParentTab();
        int indexToShow = -1;
        if (parent != null) {
            indexToShow = mTabControl.getTabIndex(parent);
        } else {
            final int currentIndex = mTabControl.getCurrentIndex();
            // Try to move to the tab to the right
            indexToShow = currentIndex + 1;
            if (indexToShow > mTabControl.getTabCount() - 1) {
                // Try to move to the tab to the left
                indexToShow = currentIndex - 1;
            }
        }
        if (switchToTab(indexToShow)) {
            // Close window
            closeTab(current);
        }
    }

    private ActiveTabsPage mActiveTabsPage;

    /**
     * Remove the active tabs page.
     * @param needToAttach If true, the active tabs page did not attach a tab
     *                     to the content view, so we need to do that here.
     */
    /* package */ void removeActiveTabPage(boolean needToAttach) {
        mContentView.removeView(mActiveTabsPage);
        mTitleBar.setVisibility(View.VISIBLE);
        mActiveTabsPage = null;
        mMenuState = R.id.MAIN_MENU;
        if (needToAttach) {
            attachTabToContentView(mTabControl.getCurrentTab());
        }
        getTopWindow().requestFocus();
    }

    @Override
    public ActionMode onStartActionMode(ActionMode.Callback callback) {
        mActionMode = super.onStartActionMode(callback);
        hideFakeTitleBar();
        // Would like to change the MENU, but onEndActionMode may not be called
        return mActionMode;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // check the action bar button before mCanChord check, as the prepare call
        // doesn't come for action bar buttons
        if (item.getItemId() == R.id.newtab) {
            openTabToHomePage();
            return true;
        }
        if (!mCanChord) {
            // The user has already fired a shortcut with this hold down of the
            // menu key.
            return false;
        }
        if (null == getTopWindow()) {
            return false;
        }
        if (mMenuIsDown) {
            // The shortcut action consumes the MENU. Even if it is still down,
            // it won't trigger the next shortcut action. In the case of the
            // shortcut action triggering a new activity, like Bookmarks, we
            // won't get onKeyUp for MENU. So it is important to reset it here.
            mMenuIsDown = false;
        }
        switch (item.getItemId()) {
            // -- Main menu
            case R.id.new_tab_menu_id:
                openTabToHomePage();
                break;

            case R.id.incognito_menu_id:
                openIncognitoTab();
                break;

            case R.id.goto_menu_id:
                editUrl();
                break;

            case R.id.bookmarks_menu_id:
                bookmarksOrHistoryPicker(false, false);
                break;

            case R.id.active_tabs_menu_id:
                mActiveTabsPage = new ActiveTabsPage(this, mTabControl);
                removeTabFromContentView(mTabControl.getCurrentTab());
                mTitleBar.setVisibility(View.GONE);
                hideFakeTitleBar();
                mContentView.addView(mActiveTabsPage, COVER_SCREEN_PARAMS);
                mActiveTabsPage.requestFocus();
                mMenuState = EMPTY_MENU;
                break;

            case R.id.add_bookmark_menu_id:
                bookmarkCurrentPage();
                break;

            case R.id.stop_reload_menu_id:
                if (mInLoad) {
                    stopLoading();
                } else {
                    getTopWindow().reload();
                }
                break;

            case R.id.back_menu_id:
                getTopWindow().goBack();
                break;

            case R.id.forward_menu_id:
                getTopWindow().goForward();
                break;

            case R.id.close_menu_id:
                // Close the subwindow if it exists.
                if (mTabControl.getCurrentSubWindow() != null) {
                    dismissSubWindow(mTabControl.getCurrentTab());
                    break;
                }
                closeCurrentWindow();
                break;

            case R.id.homepage_menu_id:
                Tab current = mTabControl.getCurrentTab();
                if (current != null) {
                    dismissSubWindow(current);
                    loadUrl(current.getWebView(), mSettings.getHomePage());
                }
                break;

            case R.id.preferences_menu_id:
                Intent intent = new Intent(this,
                        BrowserPreferencesPage.class);
                intent.putExtra(BrowserPreferencesPage.CURRENT_PAGE,
                        getTopWindow().getUrl());
                startActivityForResult(intent, PREFERENCES_PAGE);
                break;

            case R.id.find_menu_id:
                getTopWindow().showFindDialog(null);
                break;

            case R.id.save_webarchive_menu_id:
                if (LOGD_ENABLED) {
                    Log.d(LOGTAG, "Save as Web Archive");
                }
                String state = Environment.getExternalStorageState();
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                    String directory = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + File.separator;
                    File dir = new File(directory);
                    if (!dir.exists() && !dir.mkdirs()) {
                      Log.e(LOGTAG, "Save as Web Archive: mkdirs for " + directory + " failed!");
                      Toast.makeText(BrowserActivity.this, R.string.webarchive_failed,
                          Toast.LENGTH_SHORT).show();
                      break;
                    }
                    getTopWindow().saveWebArchive(directory, true, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value != null) {
                                Toast.makeText(BrowserActivity.this, R.string.webarchive_saved,
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(BrowserActivity.this, R.string.webarchive_failed,
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } else {
                    Toast.makeText(BrowserActivity.this, R.string.webarchive_failed,
                            Toast.LENGTH_SHORT).show();
                }
                break;

            case R.id.page_info_menu_id:
                showPageInfo(mTabControl.getCurrentTab(), false);
                break;

            case R.id.classic_history_menu_id:
                bookmarksOrHistoryPicker(true, false);
                break;

            case R.id.title_bar_share_page_url:
            case R.id.share_page_menu_id:
                Tab currentTab = mTabControl.getCurrentTab();
                if (null == currentTab) {
                    mCanChord = false;
                    return false;
                }
                currentTab.populatePickerData();
                sharePage(this, currentTab.getTitle(),
                        currentTab.getUrl(), currentTab.getFavicon(),
                        createScreenshot(currentTab.getWebView(), getDesiredThumbnailWidth(this),
                                getDesiredThumbnailHeight(this)));
                break;

            case R.id.dump_nav_menu_id:
                getTopWindow().debugDump();
                break;

            case R.id.dump_counters_menu_id:
                getTopWindow().dumpV8Counters();
                break;

            case R.id.zoom_in_menu_id:
                getTopWindow().zoomIn();
                break;

            case R.id.zoom_out_menu_id:
                getTopWindow().zoomOut();
                break;

            case R.id.view_downloads_menu_id:
                viewDownloads();
                break;

            case R.id.window_one_menu_id:
            case R.id.window_two_menu_id:
            case R.id.window_three_menu_id:
            case R.id.window_four_menu_id:
            case R.id.window_five_menu_id:
            case R.id.window_six_menu_id:
            case R.id.window_seven_menu_id:
            case R.id.window_eight_menu_id:
                {
                    int menuid = item.getItemId();
                    for (int id = 0; id < WINDOW_SHORTCUT_ID_ARRAY.length; id++) {
                        if (WINDOW_SHORTCUT_ID_ARRAY[id] == menuid) {
                            Tab desiredTab = mTabControl.getTab(id);
                            if (desiredTab != null &&
                                    desiredTab != mTabControl.getCurrentTab()) {
                                switchToTab(id);
                            }
                            break;
                        }
                    }
                }
                break;

            default:
                if (!super.onOptionsItemSelected(item)) {
                    return false;
                }
                // Otherwise fall through.
        }
        mCanChord = false;
        return true;
    }

    /* package */ void bookmarkCurrentPage() {
        Intent i = new Intent(BrowserActivity.this,
                AddBookmarkPage.class);
        WebView w = getTopWindow();
        i.putExtra("url", w.getUrl());
        i.putExtra("title", w.getTitle());
        i.putExtra("touch_icon_url", w.getTouchIconUrl());
        i.putExtra("thumbnail", createScreenshot(w, getDesiredThumbnailWidth(this),
                getDesiredThumbnailHeight(this)));
        startActivity(i);
    }

    /*
     * True if a custom ActionMode (i.e. find or select) is in use.
     */
    private boolean isInCustomActionMode() {
        return mActionMode != null;
    }

    /*
     * End the current ActionMode.
     */
    void endActionMode() {
        if (mActionMode != null) {
            ActionMode mode = mActionMode;
            onEndActionMode();
            mode.finish();
        }
    }

    /*
     * Called by find and select when they are finished.  Replace title bars
     * as necessary.
     */
    public void onEndActionMode() {
        if (!isInCustomActionMode()) return;
        if (mInLoad) {
            // The title bar was hidden, because otherwise it would cover up the
            // find or select dialog. Now that the dialog has been removed,
            // show the fake title bar once again.
            showFakeTitleBar();
        }
        // Would like to return the menu state to normal, but this does not
        // necessarily get called.
        mActionMode = null;
    }

    // For select and find, we keep track of the ActionMode so that
    // finish() can be called as desired.
    private ActionMode mActionMode;

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // This happens when the user begins to hold down the menu key, so
        // allow them to chord to get a shortcut.
        mCanChord = true;
        // Note: setVisible will decide whether an item is visible; while
        // setEnabled() will decide whether an item is enabled, which also means
        // whether the matching shortcut key will function.
        super.onPrepareOptionsMenu(menu);
        switch (mMenuState) {
            case EMPTY_MENU:
                if (mCurrentMenuState != mMenuState) {
                    menu.setGroupVisible(R.id.MAIN_MENU, false);
                    menu.setGroupEnabled(R.id.MAIN_MENU, false);
                    menu.setGroupEnabled(R.id.MAIN_SHORTCUT_MENU, false);
                }
                break;
            default:
                if (mCurrentMenuState != mMenuState) {
                    menu.setGroupVisible(R.id.MAIN_MENU, true);
                    menu.setGroupEnabled(R.id.MAIN_MENU, true);
                    menu.setGroupEnabled(R.id.MAIN_SHORTCUT_MENU, true);
                }
                final WebView w = getTopWindow();
                boolean canGoBack = false;
                boolean canGoForward = false;
                boolean isHome = false;
                if (w != null) {
                    canGoBack = w.canGoBack();
                    canGoForward = w.canGoForward();
                    isHome = mSettings.getHomePage().equals(w.getUrl());
                }
                final MenuItem back = menu.findItem(R.id.back_menu_id);
                back.setEnabled(canGoBack);

                final MenuItem home = menu.findItem(R.id.homepage_menu_id);
                home.setEnabled(!isHome);

                final MenuItem forward = menu.findItem(R.id.forward_menu_id);
                forward.setEnabled(canGoForward);

                if (!mXLargeScreenSize) {
                    final MenuItem newtab = menu.findItem(R.id.new_tab_menu_id);
                    newtab.setEnabled(mTabControl.canCreateNewTab());
                }

                // decide whether to show the share link option
                PackageManager pm = getPackageManager();
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                ResolveInfo ri = pm.resolveActivity(send, PackageManager.MATCH_DEFAULT_ONLY);
                menu.findItem(R.id.share_page_menu_id).setVisible(ri != null);

                boolean isNavDump = mSettings.isNavDump();
                final MenuItem nav = menu.findItem(R.id.dump_nav_menu_id);
                nav.setVisible(isNavDump);
                nav.setEnabled(isNavDump);

                boolean showDebugSettings = mSettings.showDebugSettings();
                final MenuItem counter = menu.findItem(R.id.dump_counters_menu_id);
                counter.setVisible(showDebugSettings);
                counter.setEnabled(showDebugSettings);

                break;
        }
        mCurrentMenuState = mMenuState;
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
        if (v instanceof TitleBarBase) {
            return;
        }
        WebView webview = (WebView) v;
        WebView.HitTestResult result = webview.getHitTestResult();
        if (result == null) {
            return;
        }

        int type = result.getType();
        if (type == WebView.HitTestResult.UNKNOWN_TYPE) {
            Log.w(LOGTAG,
                    "We should not show context menu when nothing is touched");
            return;
        }
        if (type == WebView.HitTestResult.EDIT_TEXT_TYPE) {
            // let TextView handles context menu
            return;
        }

        // Note, http://b/issue?id=1106666 is requesting that
        // an inflated menu can be used again. This is not available
        // yet, so inflate each time (yuk!)
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.browsercontext, menu);

        // Show the correct menu group
        final String extra = result.getExtra();
        menu.setGroupVisible(R.id.PHONE_MENU,
                type == WebView.HitTestResult.PHONE_TYPE);
        menu.setGroupVisible(R.id.EMAIL_MENU,
                type == WebView.HitTestResult.EMAIL_TYPE);
        menu.setGroupVisible(R.id.GEO_MENU,
                type == WebView.HitTestResult.GEO_TYPE);
        menu.setGroupVisible(R.id.IMAGE_MENU,
                type == WebView.HitTestResult.IMAGE_TYPE
                || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE);
        menu.setGroupVisible(R.id.ANCHOR_MENU,
                type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE);

        // Setup custom handling depending on the type
        switch (type) {
            case WebView.HitTestResult.PHONE_TYPE:
                menu.setHeaderTitle(Uri.decode(extra));
                menu.findItem(R.id.dial_context_menu_id).setIntent(
                        new Intent(Intent.ACTION_VIEW, Uri
                                .parse(WebView.SCHEME_TEL + extra)));
                Intent addIntent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
                addIntent.putExtra(Insert.PHONE, Uri.decode(extra));
                addIntent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
                menu.findItem(R.id.add_contact_context_menu_id).setIntent(
                        addIntent);
                menu.findItem(R.id.copy_phone_context_menu_id).setOnMenuItemClickListener(
                        new Copy(extra));
                break;

            case WebView.HitTestResult.EMAIL_TYPE:
                menu.setHeaderTitle(extra);
                menu.findItem(R.id.email_context_menu_id).setIntent(
                        new Intent(Intent.ACTION_VIEW, Uri
                                .parse(WebView.SCHEME_MAILTO + extra)));
                menu.findItem(R.id.copy_mail_context_menu_id).setOnMenuItemClickListener(
                        new Copy(extra));
                break;

            case WebView.HitTestResult.GEO_TYPE:
                menu.setHeaderTitle(extra);
                menu.findItem(R.id.map_context_menu_id).setIntent(
                        new Intent(Intent.ACTION_VIEW, Uri
                                .parse(WebView.SCHEME_GEO
                                        + URLEncoder.encode(extra))));
                menu.findItem(R.id.copy_geo_context_menu_id).setOnMenuItemClickListener(
                        new Copy(extra));
                break;

            case WebView.HitTestResult.SRC_ANCHOR_TYPE:
            case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE:
                TextView titleView = (TextView) LayoutInflater.from(this)
                        .inflate(android.R.layout.browser_link_context_header,
                        null);
                titleView.setText(extra);
                menu.setHeaderView(titleView);
                // decide whether to show the open link in new tab option
                boolean showNewTab = mTabControl.canCreateNewTab();
                MenuItem newTabItem
                        = menu.findItem(R.id.open_newtab_context_menu_id);
                newTabItem.setVisible(showNewTab);
                if (showNewTab) {
                    newTabItem.setOnMenuItemClickListener(
                            new MenuItem.OnMenuItemClickListener() {
                                public boolean onMenuItemClick(MenuItem item) {
                                    final Tab parent = mTabControl.getCurrentTab();
                                    final Tab newTab = openTab(extra, false);
                                    if (newTab != parent) {
                                        parent.addChildTab(newTab);
                                    }
                                    return true;
                                }
                            });
                }
                menu.findItem(R.id.bookmark_context_menu_id).setVisible(
                        Bookmarks.urlHasAcceptableScheme(extra));
                PackageManager pm = getPackageManager();
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                ResolveInfo ri = pm.resolveActivity(send, PackageManager.MATCH_DEFAULT_ONLY);
                menu.findItem(R.id.share_link_context_menu_id).setVisible(ri != null);
                if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    break;
                }
                // otherwise fall through to handle image part
            case WebView.HitTestResult.IMAGE_TYPE:
                if (type == WebView.HitTestResult.IMAGE_TYPE) {
                    menu.setHeaderTitle(extra);
                }
                menu.findItem(R.id.view_image_context_menu_id).setIntent(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(extra)));
                menu.findItem(R.id.download_context_menu_id).
                        setOnMenuItemClickListener(new Download(extra));
                menu.findItem(R.id.set_wallpaper_context_menu_id).
                        setOnMenuItemClickListener(new SetAsWallpaper(extra));
                break;

            default:
                Log.w(LOGTAG, "We should not get here.");
                break;
        }
        hideFakeTitleBar();
    }

    // Attach the given tab to the content view.
    // this should only be called for the current tab.
    private void attachTabToContentView(Tab t) {
        // Attach the container that contains the main WebView and any other UI
        // associated with the tab.
        t.attachTabToContentView(mContentView);

        if (mShouldShowErrorConsole) {
            ErrorConsoleView errorConsole = t.getErrorConsole(true);
            if (errorConsole.numberOfErrors() == 0) {
                errorConsole.showConsole(ErrorConsoleView.SHOW_NONE);
            } else {
                errorConsole.showConsole(ErrorConsoleView.SHOW_MINIMIZED);
            }

            mErrorConsoleContainer.addView(errorConsole,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                  ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        WebView view = t.getWebView();
        view.setEmbeddedTitleBar(mTitleBar);
        if (t.isInVoiceSearchMode()) {
            showVoiceTitleBar(t.getVoiceDisplayTitle());
        } else {
            revertVoiceTitleBar();
        }
        // Request focus on the top window.
        t.getTopWindow().requestFocus();
        if (mTabControl.getTabChangeListener() != null) {
            mTabControl.getTabChangeListener().onCurrentTab(t);
        }
    }

    // Attach a sub window to the main WebView of the given tab.
    void attachSubWindow(Tab t) {
        t.attachSubWindow(mContentView);
        getTopWindow().requestFocus();
    }

    // Remove the given tab from the content view.
    private void removeTabFromContentView(Tab t) {
        // Remove the container that contains the main WebView.
        t.removeTabFromContentView(mContentView);

        ErrorConsoleView errorConsole = t.getErrorConsole(false);
        if (errorConsole != null) {
            mErrorConsoleContainer.removeView(errorConsole);
        }

        WebView view = t.getWebView();
        if (view != null) {
            view.setEmbeddedTitleBar(null);
        }
    }

    // Remove the sub window if it exists. Also called by TabControl when the
    // user clicks the 'X' to dismiss a sub window.
    /* package */ void dismissSubWindow(Tab t) {
        t.removeSubWindow(mContentView);
        // dismiss the subwindow. This will destroy the WebView.
        t.dismissSubWindow();
        getTopWindow().requestFocus();
    }

    // A wrapper function of {@link #openTabAndShow(UrlData, boolean, String)}
    // that accepts url as string.
    private Tab openTabAndShow(String url, boolean closeOnExit, String appId) {
        return openTabAndShow(new UrlData(url), closeOnExit, appId);
    }

    // This method does a ton of stuff. It will attempt to create a new tab
    // if we haven't reached MAX_TABS. Otherwise it uses the current tab. If
    // url isn't null, it will load the given url.
    /* package */Tab openTabAndShow(UrlData urlData, boolean closeOnExit,
            String appId) {
        final Tab currentTab = mTabControl.getCurrentTab();
        if (mTabControl.canCreateNewTab()) {
            final Tab tab = mTabControl.createNewTab(closeOnExit, appId,
                    urlData.mUrl, false);
            WebView webview = tab.getWebView();
            // If the last tab was removed from the active tabs page, currentTab
            // will be null.
            if (currentTab != null) {
                removeTabFromContentView(currentTab);
            }
            // We must set the new tab as the current tab to reflect the old
            // animation behavior.
            mTabControl.setCurrentTab(tab);
            attachTabToContentView(tab);
            if (!urlData.isEmpty()) {
                loadUrlDataIn(tab, urlData);
            }
            return tab;
        } else {
            // Get rid of the subwindow if it exists
            dismissSubWindow(currentTab);
            if (!urlData.isEmpty()) {
                // Load the given url.
                loadUrlDataIn(currentTab, urlData);
            }
            return currentTab;
        }
    }

    private Tab openTab(String url, boolean forceForeground) {
        if (mSettings.openInBackground() && !forceForeground) {
            Tab t = mTabControl.createNewTab();
            if (t != null) {
                WebView view = t.getWebView();
                loadUrl(view, url);
            }
            return t;
        } else {
            return openTabAndShow(url, false, null);
        }
    }

    /* package */ Tab openIncognitoTab() {
        if (mTabControl.canCreateNewTab()) {
            Tab currentTab = mTabControl.getCurrentTab();
            Tab tab = mTabControl.createNewTab(false, null, null, true);
            if (currentTab != null) {
                removeTabFromContentView(currentTab);
            }
            mTabControl.setCurrentTab(tab);
            attachTabToContentView(tab);
            return tab;
        }
        return null;
    }

    private class Copy implements OnMenuItemClickListener {
        private CharSequence mText;

        public boolean onMenuItemClick(MenuItem item) {
            copy(mText);
            return true;
        }

        public Copy(CharSequence toCopy) {
            mText = toCopy;
        }
    }

    private class Download implements OnMenuItemClickListener {
        private String mText;

        public boolean onMenuItemClick(MenuItem item) {
            onDownloadStartNoStream(mText, null, null, null, -1);
            return true;
        }

        public Download(String toDownload) {
            mText = toDownload;
        }
    }

    private class SetAsWallpaper extends Thread implements
            OnMenuItemClickListener, DialogInterface.OnCancelListener {
        private URL mUrl;
        private ProgressDialog mWallpaperProgress;
        private boolean mCanceled = false;

        public SetAsWallpaper(String url) {
            try {
                mUrl = new URL(url);
            } catch (MalformedURLException e) {
                mUrl = null;
            }
        }

        public void onCancel(DialogInterface dialog) {
            mCanceled = true;
        }

        public boolean onMenuItemClick(MenuItem item) {
            if (mUrl != null) {
                // The user may have tried to set a image with a large file size as their
                // background so it may take a few moments to perform the operation. Display
                // a progress spinner while it is working.
                mWallpaperProgress = new ProgressDialog(BrowserActivity.this);
                mWallpaperProgress.setIndeterminate(true);
                mWallpaperProgress.setMessage(getText(R.string.progress_dialog_setting_wallpaper));
                mWallpaperProgress.setCancelable(true);
                mWallpaperProgress.setOnCancelListener(this);
                mWallpaperProgress.show();
                start();
            }
            return true;
        }

        @Override
        public void run() {
            Drawable oldWallpaper = BrowserActivity.this.getWallpaper();
            try {
                // TODO: This will cause the resource to be downloaded again, when we
                // should in most cases be able to grab it from the cache. To fix this
                // we should query WebCore to see if we can access a cached version and
                // instead open an input stream on that. This pattern could also be used
                // in the download manager where the same problem exists.
                InputStream inputstream = mUrl.openStream();
                if (inputstream != null) {
                    setWallpaper(inputstream);
                }
            } catch (IOException e) {
                Log.e(LOGTAG, "Unable to set new wallpaper");
                // Act as though the user canceled the operation so we try to
                // restore the old wallpaper.
                mCanceled = true;
            }

            if (mCanceled) {
                // Restore the old wallpaper if the user cancelled whilst we were setting
                // the new wallpaper.
                int width = oldWallpaper.getIntrinsicWidth();
                int height = oldWallpaper.getIntrinsicHeight();
                Bitmap bm = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(bm);
                oldWallpaper.setBounds(0, 0, width, height);
                oldWallpaper.draw(canvas);
                try {
                    setWallpaper(bm);
                } catch (IOException e) {
                    Log.e(LOGTAG, "Unable to restore old wallpaper.");
                }
                mCanceled = false;
            }

            if (mWallpaperProgress.isShowing()) {
                mWallpaperProgress.dismiss();
            }
        }
    }

    private void copy(CharSequence text) {
        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setText(text);
    }

    /**
     * Resets the browser title-view to whatever it must be
     * (for example, if we had a loading error)
     * When we have a new page, we call resetTitle, when we
     * have to reset the titlebar to whatever it used to be
     * (for example, if the user chose to stop loading), we
     * call resetTitleAndRevertLockIcon.
     */
    /* package */ void resetTitleAndRevertLockIcon() {
        mTabControl.getCurrentTab().revertLockIcon();
        updateLockIconToLatest();
        resetTitleIconAndProgress();
    }

    /**
     * Reset the title, favicon, and progress.
     */
    private void resetTitleIconAndProgress() {
        WebView current = mTabControl.getCurrentWebView();
        if (current == null) {
            return;
        }
        resetTitleAndIcon(current);
        int progress = current.getProgress();
        current.getWebChromeClient().onProgressChanged(current, progress);
    }

    // Reset the title and the icon based on the given item.
    private void resetTitleAndIcon(WebView view) {
        WebHistoryItem item = view.copyBackForwardList().getCurrentItem();
        if (item != null) {
            setUrlTitle(item.getUrl(), item.getTitle());
            setFavicon(item.getFavicon());
        } else {
            setUrlTitle(null, null);
            setFavicon(null);
        }
    }

    /**
     * Sets a title composed of the URL and the title string.
     * @param url The URL of the site being loaded.
     * @param title The title of the site being loaded.
     */
    void setUrlTitle(String url, String title) {
        mUrl = url;
        mTitle = title;

        // If we are in voice search mode, the title has already been set.
        if (mTabControl.getCurrentTab().isInVoiceSearchMode()) return;
        mTitleBar.setDisplayTitle(url);
        mFakeTitleBar.setDisplayTitle(url);
    }

    /**
     * @param url The URL to build a title version of the URL from.
     * @return The title version of the URL or null if fails.
     * The title version of the URL can be either the URL hostname,
     * or the hostname with an "https://" prefix (for secure URLs),
     * or an empty string if, for example, the URL in question is a
     * file:// URL with no hostname.
     */
    /* package */ static String buildTitleUrl(String url) {
        String titleUrl = null;

        if (url != null) {
            try {
                // parse the url string
                URL urlObj = new URL(url);
                if (urlObj != null) {
                    titleUrl = "";

                    String protocol = urlObj.getProtocol();
                    String host = urlObj.getHost();

                    if (host != null && 0 < host.length()) {
                        titleUrl = host;
                        if (protocol != null) {
                            // if a secure site, add an "https://" prefix!
                            if (protocol.equalsIgnoreCase("https")) {
                                titleUrl = protocol + "://" + host;
                            }
                        }
                    }
                }
            } catch (MalformedURLException e) {}
        }

        return titleUrl;
    }

    // Set the favicon in the title bar.
    void setFavicon(Bitmap icon) {
        mTitleBar.setFavicon(icon);
        mFakeTitleBar.setFavicon(icon);
    }

    /**
     * Close the tab, remove its associated title bar, and adjust mTabControl's
     * current tab to a valid value.
     */
    /* package */ void closeTab(Tab t) {
        int currentIndex = mTabControl.getCurrentIndex();
        int removeIndex = mTabControl.getTabIndex(t);
        mTabControl.removeTab(t);
        if (currentIndex >= removeIndex && currentIndex != 0) {
            currentIndex--;
        }
        mTabControl.setCurrentTab(mTabControl.getTab(currentIndex));
        resetTitleIconAndProgress();
        updateLockIconToLatest();

        if (!mTabControl.hasAnyOpenIncognitoTabs()) {
            WebView.cleanupPrivateBrowsingFiles(this);
        }
    }

    /* package */ void goBackOnePageOrQuit() {
        Tab current = mTabControl.getCurrentTab();
        if (current == null) {
            /*
             * Instead of finishing the activity, simply push this to the back
             * of the stack and let ActivityManager to choose the foreground
             * activity. As BrowserActivity is singleTask, it will be always the
             * root of the task. So we can use either true or false for
             * moveTaskToBack().
             */
            moveTaskToBack(true);
            return;
        }
        WebView w = current.getWebView();
        if (w.canGoBack()) {
            w.goBack();
        } else {
            // Check to see if we are closing a window that was created by
            // another window. If so, we switch back to that window.
            Tab parent = current.getParentTab();
            if (parent != null) {
                switchToTab(mTabControl.getTabIndex(parent));
                // Now we close the other tab
                closeTab(current);
            } else {
                if (current.closeOnExit()) {
                    // force the tab's inLoad() to be false as we are going to
                    // either finish the activity or remove the tab. This will
                    // ensure pauseWebViewTimers() taking action.
                    mTabControl.getCurrentTab().clearInLoad();
                    if (mTabControl.getTabCount() == 1) {
                        finish();
                        return;
                    }
                    // call pauseWebViewTimers() now, we won't be able to call
                    // it in onPause() as the WebView won't be valid.
                    // Temporarily change mActivityInPause to be true as
                    // pauseWebViewTimers() will do nothing if mActivityInPause
                    // is false.
                    boolean savedState = mActivityInPause;
                    if (savedState) {
                        Log.e(LOGTAG, "BrowserActivity is already paused "
                                + "while handing goBackOnePageOrQuit.");
                    }
                    mActivityInPause = true;
                    pauseWebViewTimers();
                    mActivityInPause = savedState;
                    removeTabFromContentView(current);
                    mTabControl.removeTab(current);
                }
                /*
                 * Instead of finishing the activity, simply push this to the back
                 * of the stack and let ActivityManager to choose the foreground
                 * activity. As BrowserActivity is singleTask, it will be always the
                 * root of the task. So we can use either true or false for
                 * moveTaskToBack().
                 */
                moveTaskToBack(true);
            }
        }
    }

    boolean isMenuDown() {
        return mMenuIsDown;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Even if MENU is already held down, we need to call to super to open
        // the IME on long press.
        if (KeyEvent.KEYCODE_MENU == keyCode) {
            mMenuIsDown = true;
            return super.onKeyDown(keyCode, event);
        }
        // The default key mode is DEFAULT_KEYS_SEARCH_LOCAL. As the MENU is
        // still down, we don't want to trigger the search. Pretend to consume
        // the key and do nothing.
        if (mMenuIsDown) return true;

        switch(keyCode) {
            case KeyEvent.KEYCODE_SPACE:
                // WebView/WebTextView handle the keys in the KeyDown. As
                // the Activity's shortcut keys are only handled when WebView
                // doesn't, have to do it in onKeyDown instead of onKeyUp.
                if (event.isShiftPressed()) {
                    getTopWindow().pageUp(false);
                } else {
                    getTopWindow().pageDown(false);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0) {
                    event.startTracking();
                    return true;
                } else if (mCustomView == null && mActiveTabsPage == null
                        && event.isLongPress()) {
                    bookmarksOrHistoryPicker(true, false);
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_MENU:
                mMenuIsDown = false;
                break;
            case KeyEvent.KEYCODE_BACK:
                if (event.isTracking() && !event.isCanceled()) {
                    if (mCustomView != null) {
                        // if a custom view is showing, hide it
                        mTabControl.getCurrentWebView().getWebChromeClient()
                                .onHideCustomView();
                    } else if (mActiveTabsPage != null) {
                        // if tab page is showing, hide it
                        removeActiveTabPage(true);
                    } else {
                        WebView subwindow = mTabControl.getCurrentSubWindow();
                        if (subwindow != null) {
                            if (subwindow.canGoBack()) {
                                subwindow.goBack();
                            } else {
                                dismissSubWindow(mTabControl.getCurrentTab());
                            }
                        } else {
                            goBackOnePageOrQuit();
                        }
                    }
                    return true;
                }
                break;
        }
        return super.onKeyUp(keyCode, event);
    }

    /* package */ void stopLoading() {
        mDidStopLoad = true;
        resetTitleAndRevertLockIcon();
        WebView w = getTopWindow();
        w.stopLoading();
        // FIXME: before refactor, it is using mWebViewClient. So I keep the
        // same logic here. But for subwindow case, should we call into the main
        // WebView's onPageFinished as we never call its onPageStarted and if
        // the page finishes itself, we don't call onPageFinished.
        mTabControl.getCurrentWebView().getWebViewClient().onPageFinished(w,
                w.getUrl());

        cancelStopToast();
        mStopToast = Toast
                .makeText(this, R.string.stopping, Toast.LENGTH_SHORT);
        mStopToast.show();
    }

    boolean didUserStopLoading() {
        return mDidStopLoad;
    }

    private void cancelStopToast() {
        if (mStopToast != null) {
            mStopToast.cancel();
            mStopToast = null;
        }
    }

    // called by a UI or non-UI thread to post the message
    public void postMessage(int what, int arg1, int arg2, Object obj,
            long delayMillis) {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(what, arg1, arg2,
                obj), delayMillis);
    }

    // called by a UI or non-UI thread to remove the message
    void removeMessages(int what, Object object) {
        mHandler.removeMessages(what, object);
    }

    // public message ids
    public final static int LOAD_URL                = 1001;
    public final static int STOP_LOAD               = 1002;

    // Message Ids
    private static final int FOCUS_NODE_HREF         = 102;
    private static final int RELEASE_WAKELOCK        = 107;

    static final int UPDATE_BOOKMARK_THUMBNAIL       = 108;

    private static final int TOUCH_ICON_DOWNLOADED   = 109;

    private static final int OPEN_BOOKMARKS = 201;

    // Private handler for handling javascript and saving passwords
    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OPEN_BOOKMARKS:
                    bookmarksOrHistoryPicker(false, false);
                    break;
                case FOCUS_NODE_HREF:
                {
                    String url = (String) msg.getData().get("url");
                    String title = (String) msg.getData().get("title");
                    if (url == null || url.length() == 0) {
                        break;
                    }
                    HashMap focusNodeMap = (HashMap) msg.obj;
                    WebView view = (WebView) focusNodeMap.get("webview");
                    // Only apply the action if the top window did not change.
                    if (getTopWindow() != view) {
                        break;
                    }
                    switch (msg.arg1) {
                        case R.id.open_context_menu_id:
                        case R.id.view_image_context_menu_id:
                            loadUrlFromContext(getTopWindow(), url);
                            break;
                        case R.id.bookmark_context_menu_id:
                            Intent intent = new Intent(BrowserActivity.this,
                                    AddBookmarkPage.class);
                            intent.putExtra("url", url);
                            intent.putExtra("title", title);
                            startActivity(intent);
                            break;
                        case R.id.share_link_context_menu_id:
                            sharePage(BrowserActivity.this, title, url, null,
                                    null);
                            break;
                        case R.id.copy_link_context_menu_id:
                            copy(url);
                            break;
                        case R.id.save_link_context_menu_id:
                        case R.id.download_context_menu_id:
                            onDownloadStartNoStream(url, null, null, null, -1);
                            break;
                    }
                    break;
                }

                case LOAD_URL:
                    loadUrlFromContext(getTopWindow(), (String) msg.obj);
                    break;

                case STOP_LOAD:
                    stopLoading();
                    break;

                case RELEASE_WAKELOCK:
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                        // if we reach here, Browser should be still in the
                        // background loading after WAKELOCK_TIMEOUT (5-min).
                        // To avoid burning the battery, stop loading.
                        mTabControl.stopAllLoading();
                    }
                    break;

                case UPDATE_BOOKMARK_THUMBNAIL:
                    WebView view = (WebView) msg.obj;
                    if (view != null) {
                        updateScreenshot(view);
                    }
                    break;

                case TOUCH_ICON_DOWNLOADED:
                    Bundle b = msg.getData();
                    showSaveToHomescreenDialog(b.getString("url"),
                        b.getString("title"),
                        (Bitmap) b.getParcelable("touchIcon"),
                        (Bitmap) b.getParcelable("favicon"));
                    break;
            }
        }
    };

    /**
     * Share a page, providing the title, url, favicon, and a screenshot.  Uses
     * an {@link Intent} to launch the Activity chooser.
     * @param c Context used to launch a new Activity.
     * @param title Title of the page.  Stored in the Intent with
     *          {@link Intent#EXTRA_SUBJECT}
     * @param url URL of the page.  Stored in the Intent with
     *          {@link Intent#EXTRA_TEXT}
     * @param favicon Bitmap of the favicon for the page.  Stored in the Intent
     *          with {@link Browser#EXTRA_SHARE_FAVICON}
     * @param screenshot Bitmap of a screenshot of the page.  Stored in the
     *          Intent with {@link Browser#EXTRA_SHARE_SCREENSHOT}
     */
    public static final void sharePage(Context c, String title, String url,
            Bitmap favicon, Bitmap screenshot) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.putExtra(Browser.EXTRA_SHARE_FAVICON, favicon);
        send.putExtra(Browser.EXTRA_SHARE_SCREENSHOT, screenshot);
        try {
            c.startActivity(Intent.createChooser(send, c.getString(
                    R.string.choosertitle_sharevia)));
        } catch(android.content.ActivityNotFoundException ex) {
            // if no app handles it, do nothing
        }
    }

    private void updateScreenshot(WebView view) {
        // If this is a bookmarked site, add a screenshot to the database.
        // FIXME: When should we update?  Every time?
        // FIXME: Would like to make sure there is actually something to
        // draw, but the API for that (WebViewCore.pictureReady()) is not
        // currently accessible here.

        final Bitmap bm = createScreenshot(view, getDesiredThumbnailWidth(this),
                getDesiredThumbnailHeight(this));
        if (bm == null) {
            return;
        }

        final ContentResolver cr = getContentResolver();
        final String url = view.getUrl();
        final String originalUrl = view.getOriginalUrl();

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... unused) {
                Cursor cursor = null;
                try {
                    cursor = Bookmarks.queryCombinedForUrl(cr, originalUrl, url);
                    if (cursor != null && cursor.moveToFirst()) {
                        final ByteArrayOutputStream os = new ByteArrayOutputStream();
                        bm.compress(Bitmap.CompressFormat.PNG, 100, os);

                        ContentValues values = new ContentValues();
                        values.put(Images.THUMBNAIL, os.toByteArray());
                        values.put(Images.URL, cursor.getString(0));

                        do {
                            cr.update(Images.CONTENT_URI, values, null, null);
                        } while (cursor.moveToNext());
                    }
                } catch (IllegalStateException e) {
                    // Ignore
                } finally {
                    if (cursor != null) cursor.close();
                }
                return null;
            }
        }.execute();
    }

    /**
     * Return the desired width for thumbnail screenshots, which are stored in
     * the database, and used on the bookmarks screen.
     * @param context Context for finding out the density of the screen.
     * @return desired width for thumbnail screenshot.
     */
    /* package */ static int getDesiredThumbnailWidth(Context context) {
        return context.getResources().getDimensionPixelOffset(R.dimen.bookmarkThumbnailWidth);
    }

    /**
     * Return the desired height for thumbnail screenshots, which are stored in
     * the database, and used on the bookmarks screen.
     * @param context Context for finding out the density of the screen.
     * @return desired height for thumbnail screenshot.
     */
    /* package */ static int getDesiredThumbnailHeight(Context context) {
        return context.getResources().getDimensionPixelOffset(R.dimen.bookmarkThumbnailHeight);
    }

    private Bitmap createScreenshot(WebView view, int width, int height) {
        Picture thumbnail = view.capturePicture();
        if (thumbnail == null) {
            return null;
        }
        Bitmap bm = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bm);
        // May need to tweak these values to determine what is the
        // best scale factor
        int thumbnailWidth = thumbnail.getWidth();
        int thumbnailHeight = thumbnail.getHeight();
        float scaleFactorX = 1.0f;
        float scaleFactorY = 1.0f;
        if (thumbnailWidth > 0) {
            scaleFactorX = (float) width / (float)thumbnailWidth;
        } else {
            return null;
        }

        if (view.getWidth() > view.getHeight() &&
                thumbnailHeight < view.getHeight() && thumbnailHeight > 0) {
            // If the device is in landscape and the page is shorter
            // than the height of the view, stretch the thumbnail to fill the
            // space.
            scaleFactorY = (float) height / (float)thumbnailHeight;
        } else {
            // In the portrait case, this looks nice.
            scaleFactorY = scaleFactorX;
        }

        canvas.scale(scaleFactorX, scaleFactorY);

        thumbnail.draw(canvas);
        return bm;
    }

    // -------------------------------------------------------------------------
    // Helper function for WebViewClient.
    //-------------------------------------------------------------------------

    // Use in overrideUrlLoading
    /* package */ final static String SCHEME_WTAI = "wtai://wp/";
    /* package */ final static String SCHEME_WTAI_MC = "wtai://wp/mc;";
    /* package */ final static String SCHEME_WTAI_SD = "wtai://wp/sd;";
    /* package */ final static String SCHEME_WTAI_AP = "wtai://wp/ap;";

    // Keep this initial progress in sync with initialProgressValue (* 100)
    // in ProgressTracker.cpp
    private final static int INITIAL_PROGRESS = 10;

    void onPageStarted(WebView view, String url, Bitmap favicon) {
        // when BrowserActivity just starts, onPageStarted may be called before
        // onResume as it is triggered from onCreate. Call resumeWebViewTimers
        // to start the timer. As we won't switch tabs while an activity is in
        // pause state, we can ensure calling resume and pause in pair.
        if (mActivityInPause) resumeWebViewTimers();

        resetLockIcon(url);
        setUrlTitle(url, null);
        setFavicon(favicon);
        // Show some progress so that the user knows the page is beginning to
        // load
        onProgressChanged(view, INITIAL_PROGRESS);
        mDidStopLoad = false;
        if (!mIsNetworkUp) createAndShowNetworkDialog();
        endActionMode();
        if (mSettings.isTracing()) {
            String host;
            try {
                WebAddress uri = new WebAddress(url);
                host = uri.mHost;
            } catch (android.net.ParseException ex) {
                host = "browser";
            }
            host = host.replace('.', '_');
            host += ".trace";
            mInTrace = true;
            Debug.startMethodTracing(host, 20 * 1024 * 1024);
        }

        // Performance probe
        if (false) {
            mStart = SystemClock.uptimeMillis();
            mProcessStart = Process.getElapsedCpuTime();
            long[] sysCpu = new long[7];
            if (Process.readProcFile("/proc/stat", SYSTEM_CPU_FORMAT, null,
                    sysCpu, null)) {
                mUserStart = sysCpu[0] + sysCpu[1];
                mSystemStart = sysCpu[2];
                mIdleStart = sysCpu[3];
                mIrqStart = sysCpu[4] + sysCpu[5] + sysCpu[6];
            }
            mUiStart = SystemClock.currentThreadTimeMillis();
        }
    }

    void onPageFinished(WebView view, String url) {
        // Reset the title and icon in case we stopped a provisional load.
        resetTitleAndIcon(view);
        // Update the lock icon image only once we are done loading
        updateLockIconToLatest();
        // pause the WebView timer and release the wake lock if it is finished
        // while BrowserActivity is in pause state.
        if (mActivityInPause && pauseWebViewTimers()) {
            if (mWakeLock.isHeld()) {
                mHandler.removeMessages(RELEASE_WAKELOCK);
                mWakeLock.release();
            }
        }

        // Performance probe
        if (false) {
            long[] sysCpu = new long[7];
            if (Process.readProcFile("/proc/stat", SYSTEM_CPU_FORMAT, null,
                    sysCpu, null)) {
                String uiInfo = "UI thread used "
                        + (SystemClock.currentThreadTimeMillis() - mUiStart)
                        + " ms";
                if (LOGD_ENABLED) {
                    Log.d(LOGTAG, uiInfo);
                }
                //The string that gets written to the log
                String performanceString = "It took total "
                        + (SystemClock.uptimeMillis() - mStart)
                        + " ms clock time to load the page."
                        + "\nbrowser process used "
                        + (Process.getElapsedCpuTime() - mProcessStart)
                        + " ms, user processes used "
                        + (sysCpu[0] + sysCpu[1] - mUserStart) * 10
                        + " ms, kernel used "
                        + (sysCpu[2] - mSystemStart) * 10
                        + " ms, idle took " + (sysCpu[3] - mIdleStart) * 10
                        + " ms and irq took "
                        + (sysCpu[4] + sysCpu[5] + sysCpu[6] - mIrqStart)
                        * 10 + " ms, " + uiInfo;
                if (LOGD_ENABLED) {
                    Log.d(LOGTAG, performanceString + "\nWebpage: " + url);
                }
                if (url != null) {
                    // strip the url to maintain consistency
                    String newUrl = new String(url);
                    if (newUrl.startsWith("http://www.")) {
                        newUrl = newUrl.substring(11);
                    } else if (newUrl.startsWith("http://")) {
                        newUrl = newUrl.substring(7);
                    } else if (newUrl.startsWith("https://www.")) {
                        newUrl = newUrl.substring(12);
                    } else if (newUrl.startsWith("https://")) {
                        newUrl = newUrl.substring(8);
                    }
                    if (LOGD_ENABLED) {
                        Log.d(LOGTAG, newUrl + " loaded");
                    }
                }
            }
         }

        if (mInTrace) {
            mInTrace = false;
            Debug.stopMethodTracing();
        }
    }

    private void closeEmptyChildTab() {
        Tab current = mTabControl.getCurrentTab();
        if (current != null
                && current.getWebView().copyBackForwardList().getSize() == 0) {
            Tab parent = current.getParentTab();
            if (parent != null) {
                switchToTab(mTabControl.getTabIndex(parent));
                closeTab(current);
            }
        }
    }

    boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (view.isPrivateBrowsingEnabled()) {
            // Don't allow urls to leave the browser app when in private browsing mode
            loadUrl(view, url);
            return true;
        }

        if (url.startsWith(SCHEME_WTAI)) {
            // wtai://wp/mc;number
            // number=string(phone-number)
            if (url.startsWith(SCHEME_WTAI_MC)) {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(WebView.SCHEME_TEL +
                        url.substring(SCHEME_WTAI_MC.length())));
                startActivity(intent);
                // before leaving BrowserActivity, close the empty child tab.
                // If a new tab is created through JavaScript open to load this
                // url, we would like to close it as we will load this url in a
                // different Activity.
                closeEmptyChildTab();
                return true;
            }
            // wtai://wp/sd;dtmf
            // dtmf=string(dialstring)
            if (url.startsWith(SCHEME_WTAI_SD)) {
                // TODO: only send when there is active voice connection
                return false;
            }
            // wtai://wp/ap;number;name
            // number=string(phone-number)
            // name=string
            if (url.startsWith(SCHEME_WTAI_AP)) {
                // TODO
                return false;
            }
        }

        // The "about:" schemes are internal to the browser; don't want these to
        // be dispatched to other apps.
        if (url.startsWith("about:")) {
            return false;
        }

        // If this is a Google search, attempt to add an RLZ string (if one isn't already present).
        if (rlzProviderPresent()) {
            Uri siteUri = Uri.parse(url);
            if (needsRlzString(siteUri)) {
                String rlz = null;
                Cursor cur = null;
                try {
                    cur = getContentResolver().query(getRlzUri(), null, null, null, null);
                    if (cur != null && cur.moveToFirst() && !cur.isNull(0)) {
                        url = siteUri.buildUpon()
                                     .appendQueryParameter("rlz", cur.getString(0))
                                     .build().toString();
                    }
                } finally {
                    if (cur != null) {
                        cur.close();
                    }
                }
                loadUrl(view, url);
                return true;
            }
        }

        Intent intent;
        // perform generic parsing of the URI to turn it into an Intent.
        try {
            intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException ex) {
            Log.w("Browser", "Bad URI " + url + ": " + ex.getMessage());
            return false;
        }

        // check whether the intent can be resolved. If not, we will see
        // whether we can download it from the Market.
        if (getPackageManager().resolveActivity(intent, 0) == null) {
            String packagename = intent.getPackage();
            if (packagename != null) {
                intent = new Intent(Intent.ACTION_VIEW, Uri
                        .parse("market://search?q=pname:" + packagename));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(intent);
                // before leaving BrowserActivity, close the empty child tab.
                // If a new tab is created through JavaScript open to load this
                // url, we would like to close it as we will load this url in a
                // different Activity.
                closeEmptyChildTab();
                return true;
            } else {
                return false;
            }
        }

        // sanitize the Intent, ensuring web pages can not bypass browser
        // security (only access to BROWSABLE activities).
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setComponent(null);
        try {
            if (startActivityIfNeeded(intent, -1)) {
                // before leaving BrowserActivity, close the empty child tab.
                // If a new tab is created through JavaScript open to load this
                // url, we would like to close it as we will load this url in a
                // different Activity.
                closeEmptyChildTab();
                return true;
            }
        } catch (ActivityNotFoundException ex) {
            // ignore the error. If no application can handle the URL,
            // eg about:blank, assume the browser can handle it.
        }

        if (mMenuIsDown) {
            openTab(url, false);
            closeOptionsMenu();
            return true;
        }
        return false;
    }

    // Determine whether the RLZ provider is present on the system.
    private boolean rlzProviderPresent() {
        if (mIsProviderPresent == null) {
            PackageManager pm = getPackageManager();
            mIsProviderPresent = pm.resolveContentProvider(BrowserSettings.RLZ_PROVIDER, 0) != null;
        }
        return mIsProviderPresent;
    }

    // Retrieve the RLZ access point string and cache the URI used to retrieve RLZ values.
    private Uri getRlzUri() {
        if (mRlzUri == null) {
            String ap = getResources().getString(R.string.rlz_access_point);
            mRlzUri = Uri.withAppendedPath(BrowserSettings.RLZ_PROVIDER_URI, ap);
        }
        return mRlzUri;
    }

    // Determine if this URI appears to be for a Google search and does not have an RLZ parameter.
    // Taken largely from Chrome source, src/chrome/browser/google_url_tracker.cc
    private static boolean needsRlzString(Uri uri) {
        String scheme = uri.getScheme();
        if (("http".equals(scheme) || "https".equals(scheme)) &&
            (uri.getQueryParameter("q") != null) && (uri.getQueryParameter("rlz") == null)) {
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String[] hostComponents = host.split("\\.");

            if (hostComponents.length < 2) {
                return false;
            }
            int googleComponent = hostComponents.length - 2;
            String component = hostComponents[googleComponent];
            if (!"google".equals(component)) {
                if (hostComponents.length < 3 ||
                        (!"co".equals(component) && !"com".equals(component))) {
                    return false;
                }
                googleComponent = hostComponents.length - 3;
                if (!"google".equals(hostComponents[googleComponent])) {
                    return false;
                }
            }

            // Google corp network handling.
            if (googleComponent > 0 && "corp".equals(hostComponents[googleComponent - 1])) {
                return false;
            }

            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helper function for WebChromeClient
    // -------------------------------------------------------------------------

    void onProgressChanged(WebView view, int newProgress) {

        // On the phone, the fake title bar will always cover up the
        // regular title bar (or the regular one is offscreen), so only the
        // fake title bar needs to change its progress
        mFakeTitleBar.setProgress(newProgress);

        if (newProgress == 100) {
            // onProgressChanged() may continue to be called after the main
            // frame has finished loading, as any remaining sub frames continue
            // to load. We'll only get called once though with newProgress as
            // 100 when everything is loaded. (onPageFinished is called once
            // when the main frame completes loading regardless of the state of
            // any sub frames so calls to onProgressChanges may continue after
            // onPageFinished has executed)
            if (mInLoad) {
                mInLoad = false;
                updateInLoadMenuItems();
                // If the options menu is open, leave the title bar
                if (!mOptionsMenuOpen || !mIconView) {
                    hideFakeTitleBar();
                }
            }
        } else {
            if (!mInLoad) {
                // onPageFinished may have already been called but a subframe is
                // still loading and updating the progress. Reset mInLoad and
                // update the menu items.
                mInLoad = true;
                updateInLoadMenuItems();
            }
            // When the page first begins to load, the Activity may still be
            // paused, in which case showFakeTitleBar will do nothing.  Call
            // again as the page continues to load so that it will be shown.
            // (Calling it will the fake title bar is already showing will also
            // do nothing.
            if (!mOptionsMenuOpen || mIconView) {
                // This page has begun to load, so show the title bar
                showFakeTitleBar();
            }
        }
    }

    void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        // if a view already exists then immediately terminate the new one
        if (mCustomView != null) {
            callback.onCustomViewHidden();
            return;
        }

        // Add the custom view to its container.
        mCustomViewContainer.addView(view, COVER_SCREEN_GRAVITY_CENTER);
        mCustomView = view;
        mCustomViewCallback = callback;
        // Save the menu state and set it to empty while the custom
        // view is showing.
        mOldMenuState = mMenuState;
        mMenuState = EMPTY_MENU;
        // Hide the content view.
        mContentView.setVisibility(View.GONE);
        // Finally show the custom view container.
        setStatusBarVisibility(false);
        mCustomViewContainer.setVisibility(View.VISIBLE);
        mCustomViewContainer.bringToFront();
    }

    void onHideCustomView() {
        if (mCustomView == null)
            return;

        // Hide the custom view.
        mCustomView.setVisibility(View.GONE);
        // Remove the custom view from its container.
        mCustomViewContainer.removeView(mCustomView);
        mCustomView = null;
        // Reset the old menu state.
        mMenuState = mOldMenuState;
        mOldMenuState = EMPTY_MENU;
        mCustomViewContainer.setVisibility(View.GONE);
        mCustomViewCallback.onCustomViewHidden();
        // Show the content view.
        setStatusBarVisibility(true);
        mContentView.setVisibility(View.VISIBLE);
    }

    Bitmap getDefaultVideoPoster() {
        if (mDefaultVideoPoster == null) {
            mDefaultVideoPoster = BitmapFactory.decodeResource(
                    getResources(), R.drawable.default_video_poster);
        }
        return mDefaultVideoPoster;
    }

    View getVideoLoadingProgressView() {
        if (mVideoProgressView == null) {
            LayoutInflater inflater = LayoutInflater.from(BrowserActivity.this);
            mVideoProgressView = inflater.inflate(
                    R.layout.video_loading_progress, null);
        }
        return mVideoProgressView;
    }

    /*
     * The Object used to inform the WebView of the file to upload.
     */
    private ValueCallback<Uri> mUploadMessage;
    private String mCameraFilePath;

    void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {

        final String imageMimeType = "image/*";
        final String videoMimeType = "video/*";
        final String audioMimeType = "audio/*";
        final String mediaSourceKey = "capture";
        final String mediaSourceValueCamera = "camera";
        final String mediaSourceValueFileSystem = "filesystem";
        final String mediaSourceValueCamcorder = "camcorder";
        final String mediaSourceValueMicrophone = "microphone";

        // media source can be 'filesystem' or 'camera' or 'camcorder' or 'microphone'.
        String mediaSource = "";

        // We add the camera intent if there was no accept type (or '*/*' or 'image/*').
        boolean addCameraIntent = true;
        // We add the camcorder intent if there was no accept type (or '*/*' or 'video/*').
        boolean addCamcorderIntent = true;

        if (mUploadMessage != null) {
            // Already a file picker operation in progress.
            return;
        }

        mUploadMessage = uploadMsg;

        // Parse the accept type.
        String params[] = acceptType.split(";");
        String mimeType = params[0];

        for (String p : params) {
            String[] keyValue = p.split("=");
            if (keyValue.length == 2) {
                // Process key=value parameters.
                if (mediaSourceKey.equals(keyValue[0])) {
                    mediaSource = keyValue[1];
                }
            }
        }

        // This intent will display the standard OPENABLE file picker.
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);

        // Create an intent to add to the standard file picker that will
        // capture an image from the camera. We'll combine this intent with
        // the standard OPENABLE picker unless the web developer specifically
        // requested the camera or gallery be opened by passing a parameter
        // in the accept type.
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File externalDataDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM);
        File cameraDataDir = new File(externalDataDir.getAbsolutePath() +
                File.separator + "browser-photos");
        cameraDataDir.mkdirs();
        mCameraFilePath = cameraDataDir.getAbsolutePath() + File.separator +
                System.currentTimeMillis() + ".jpg";
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(new File(mCameraFilePath)));

        Intent camcorderIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);

        Intent soundRecIntent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);

        if (mimeType.equals(imageMimeType)) {
            i.setType(imageMimeType);
            addCamcorderIntent = false;
            if (mediaSource.equals(mediaSourceValueCamera)) {
                // Specified 'image/*' and requested the camera, so go ahead and launch the camera
                // directly.
                BrowserActivity.this.startActivityForResult(cameraIntent, FILE_SELECTED);
                return;
            } else if (mediaSource.equals(mediaSourceValueFileSystem)) {
                // Specified filesytem as the source, so don't want to consider the camera.
                addCameraIntent = false;
            }
        } else if (mimeType.equals(videoMimeType)) {
            i.setType(videoMimeType);
            addCameraIntent = false;
            // The camcorder saves it's own file and returns it to us in the intent, so
            // we don't need to generate one here.
            mCameraFilePath = null;

            if (mediaSource.equals(mediaSourceValueCamcorder)) {
                // Specified 'video/*' and requested the camcorder, so go ahead and launch the
                // camcorder directly.
                BrowserActivity.this.startActivityForResult(camcorderIntent, FILE_SELECTED);
                return;
            } else if (mediaSource.equals(mediaSourceValueFileSystem)) {
                // Specified filesystem as the source, so don't want to consider the camcorder.
                addCamcorderIntent = false;
            }
        } else if (mimeType.equals(audioMimeType)) {
            i.setType(audioMimeType);
            addCameraIntent = false;
            addCamcorderIntent = false;
            if (mediaSource.equals(mediaSourceValueMicrophone)) {
                // Specified 'audio/*' and requested microphone, so go ahead and launch the sound
                // recorder.
                BrowserActivity.this.startActivityForResult(soundRecIntent, FILE_SELECTED);
                return;
            }
            // On a default system, there is no single option to open an audio "gallery". Both the
            // sound recorder and music browser respond to the OPENABLE/audio/* intent unlike the
            // image/* and video/* OPENABLE intents where the image / video gallery are the only
            // respondants (and so the user is not prompted by default).
        } else {
            i.setType("*/*");
        }

        // Combine the chooser and the extra choices (like camera or camcorder)
        Intent chooser = new Intent(Intent.ACTION_CHOOSER);
        chooser.putExtra(Intent.EXTRA_INTENT, i);

        Vector<Intent> extraInitialIntents = new Vector<Intent>(0);

        if (addCameraIntent) {
            extraInitialIntents.add(cameraIntent);
        }

        if (addCamcorderIntent) {
            extraInitialIntents.add(camcorderIntent);
        }

        if (extraInitialIntents.size() > 0) {
            Intent[] extraIntents = new Intent[extraInitialIntents.size()];
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraInitialIntents.toArray(extraIntents));
        }

        chooser.putExtra(Intent.EXTRA_TITLE, getString(R.string.choose_upload));
        BrowserActivity.this.startActivityForResult(chooser, FILE_SELECTED);
    }

    // -------------------------------------------------------------------------
    // Implement functions for DownloadListener
    // -------------------------------------------------------------------------

    /**
     * Notify the host application a download should be done, or that
     * the data should be streamed if a streaming viewer is available.
     * @param url The full url to the content that should be downloaded
     * @param contentDisposition Content-disposition http header, if
     *                           present.
     * @param mimetype The mimetype of the content reported by the server
     * @param contentLength The file size reported by the server
     */
    public void onDownloadStart(String url, String userAgent,
            String contentDisposition, String mimetype, long contentLength) {
        // if we're dealing wih A/V content that's not explicitly marked
        //     for download, check if it's streamable.
        if (contentDisposition == null
                || !contentDisposition.regionMatches(
                        true, 0, "attachment", 0, 10)) {
            // query the package manager to see if there's a registered handler
            //     that matches.
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), mimetype);
            ResolveInfo info = getPackageManager().resolveActivity(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null) {
                ComponentName myName = getComponentName();
                // If we resolved to ourselves, we don't want to attempt to
                // load the url only to try and download it again.
                if (!myName.getPackageName().equals(
                        info.activityInfo.packageName)
                        || !myName.getClassName().equals(
                                info.activityInfo.name)) {
                    // someone (other than us) knows how to handle this mime
                    // type with this scheme, don't download.
                    try {
                        startActivity(intent);
                        return;
                    } catch (ActivityNotFoundException ex) {
                        if (LOGD_ENABLED) {
                            Log.d(LOGTAG, "activity not found for " + mimetype
                                    + " over " + Uri.parse(url).getScheme(),
                                    ex);
                        }
                        // Best behavior is to fall back to a download in this
                        // case
                    }
                }
            }
        }
        onDownloadStartNoStream(url, userAgent, contentDisposition, mimetype, contentLength);
    }

    // This is to work around the fact that java.net.URI throws Exceptions
    // instead of just encoding URL's properly
    // Helper method for onDownloadStartNoStream
    private static String encodePath(String path) {
        char[] chars = path.toCharArray();

        boolean needed = false;
        for (char c : chars) {
            if (c == '[' || c == ']') {
                needed = true;
                break;
            }
        }
        if (needed == false) {
            return path;
        }

        StringBuilder sb = new StringBuilder("");
        for (char c : chars) {
            if (c == '[' || c == ']') {
                sb.append('%');
                sb.append(Integer.toHexString(c));
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /**
     * Notify the host application a download should be done, even if there
     * is a streaming viewer available for thise type.
     * @param url The full url to the content that should be downloaded
     * @param contentDisposition Content-disposition http header, if
     *                           present.
     * @param mimetype The mimetype of the content reported by the server
     * @param contentLength The file size reported by the server
     */
    /*package */ void onDownloadStartNoStream(String url, String userAgent,
            String contentDisposition, String mimetype, long contentLength) {

        String filename = URLUtil.guessFileName(url,
                contentDisposition, mimetype);

        // Check to see if we have an SDCard
        String status = Environment.getExternalStorageState();
        if (!status.equals(Environment.MEDIA_MOUNTED)) {
            int title;
            String msg;

            // Check to see if the SDCard is busy, same as the music app
            if (status.equals(Environment.MEDIA_SHARED)) {
                msg = getString(R.string.download_sdcard_busy_dlg_msg);
                title = R.string.download_sdcard_busy_dlg_title;
            } else {
                msg = getString(R.string.download_no_sdcard_dlg_msg, filename);
                title = R.string.download_no_sdcard_dlg_title;
            }

            new AlertDialog.Builder(this)
                .setTitle(title)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show();
            return;
        }

        // java.net.URI is a lot stricter than KURL so we have to encode some
        // extra characters. Fix for b 2538060 and b 1634719
        WebAddress webAddress;
        try {
            webAddress = new WebAddress(url);
            webAddress.mPath = encodePath(webAddress.mPath);
        } catch (Exception e) {
            // This only happens for very bad urls, we want to chatch the
            // exception here
            Log.e(LOGTAG, "Exception trying to parse url:" + url);
            return;
        }

        // XXX: Have to use the old url since the cookies were stored using the
        // old percent-encoded url.
        String cookies = CookieManager.getInstance().getCookie(url);

        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_URI, webAddress.toString());
        values.put(Downloads.Impl.COLUMN_COOKIE_DATA, cookies);
        values.put(Downloads.Impl.COLUMN_USER_AGENT, userAgent);
        values.put(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE,
                getPackageName());
        values.put(Downloads.Impl.COLUMN_NOTIFICATION_CLASS,
                OpenDownloadReceiver.class.getCanonicalName());
        values.put(Downloads.Impl.COLUMN_VISIBILITY,
                Downloads.Impl.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        values.put(Downloads.Impl.COLUMN_MIME_TYPE, mimetype);
        values.put(Downloads.Impl.COLUMN_FILE_NAME_HINT, filename);
        values.put(Downloads.Impl.COLUMN_DESCRIPTION, webAddress.mHost);
        if (contentLength > 0) {
            values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, contentLength);
        }
        if (mimetype == null) {
            // We must have long pressed on a link or image to download it. We
            // are not sure of the mimetype in this case, so do a head request
            new FetchUrlMimeType(this).execute(values);
        } else {
            final Uri contentUri =
                    getContentResolver().insert(Downloads.Impl.CONTENT_URI, values);
        }
        Toast.makeText(this, R.string.download_pending, Toast.LENGTH_SHORT)
                .show();
    }

    // -------------------------------------------------------------------------

    /**
     * Resets the lock icon. This method is called when we start a new load and
     * know the url to be loaded.
     */
    private void resetLockIcon(String url) {
        // Save the lock-icon state (we revert to it if the load gets cancelled)
        mTabControl.getCurrentTab().resetLockIcon(url);
        updateLockIconImage(LOCK_ICON_UNSECURE);
    }

    /**
     * Update the lock icon to correspond to our latest state.
     */
    private void updateLockIconToLatest() {
        Tab t = mTabControl.getCurrentTab();
        if (t != null) {
            updateLockIconImage(t.getLockIconType());
        }
    }

    /**
     * Updates the lock-icon image in the title-bar.
     */
    private void updateLockIconImage(int lockIconType) {
        Drawable d = null;
        if (lockIconType == LOCK_ICON_SECURE) {
            d = mSecLockIcon;
        } else if (lockIconType == LOCK_ICON_MIXED) {
            d = mMixLockIcon;
        }
        mTitleBar.setLock(d);
        mFakeTitleBar.setLock(d);
    }

    /**
     * Displays a page-info dialog.
     * @param tab The tab to show info about
     * @param fromShowSSLCertificateOnError The flag that indicates whether
     * this dialog was opened from the SSL-certificate-on-error dialog or
     * not. This is important, since we need to know whether to return to
     * the parent dialog or simply dismiss.
     */
    private void showPageInfo(final Tab tab,
                              final boolean fromShowSSLCertificateOnError) {
        final LayoutInflater factory = LayoutInflater
                .from(this);

        final View pageInfoView = factory.inflate(R.layout.page_info, null);

        final WebView view = tab.getWebView();

        String url = null;
        String title = null;

        if (view == null) {
            url = tab.getUrl();
            title = tab.getTitle();
        } else if (view == mTabControl.getCurrentWebView()) {
             // Use the cached title and url if this is the current WebView
            url = mUrl;
            title = mTitle;
        } else {
            url = view.getUrl();
            title = view.getTitle();
        }

        if (url == null) {
            url = "";
        }
        if (title == null) {
            title = "";
        }

        ((TextView) pageInfoView.findViewById(R.id.address)).setText(url);
        ((TextView) pageInfoView.findViewById(R.id.title)).setText(title);

        mPageInfoView = tab;
        mPageInfoFromShowSSLCertificateOnError = fromShowSSLCertificateOnError;

        AlertDialog.Builder alertDialogBuilder =
            new AlertDialog.Builder(this)
            .setTitle(R.string.page_info).setIcon(android.R.drawable.ic_dialog_info)
            .setView(pageInfoView)
            .setPositiveButton(
                R.string.ok,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,
                                        int whichButton) {
                        mPageInfoDialog = null;
                        mPageInfoView = null;

                        // if we came here from the SSL error dialog
                        if (fromShowSSLCertificateOnError) {
                            // go back to the SSL error dialog
                            showSSLCertificateOnError(
                                mSSLCertificateOnErrorView,
                                mSSLCertificateOnErrorHandler,
                                mSSLCertificateOnErrorError);
                        }
                    }
                })
            .setOnCancelListener(
                new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        mPageInfoDialog = null;
                        mPageInfoView = null;

                        // if we came here from the SSL error dialog
                        if (fromShowSSLCertificateOnError) {
                            // go back to the SSL error dialog
                            showSSLCertificateOnError(
                                mSSLCertificateOnErrorView,
                                mSSLCertificateOnErrorHandler,
                                mSSLCertificateOnErrorError);
                        }
                    }
                });

        // if we have a main top-level page SSL certificate set or a certificate
        // error
        if (fromShowSSLCertificateOnError ||
                (view != null && view.getCertificate() != null)) {
            // add a 'View Certificate' button
            alertDialogBuilder.setNeutralButton(
                R.string.view_certificate,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,
                                        int whichButton) {
                        mPageInfoDialog = null;
                        mPageInfoView = null;

                        // if we came here from the SSL error dialog
                        if (fromShowSSLCertificateOnError) {
                            // go back to the SSL error dialog
                            showSSLCertificateOnError(
                                mSSLCertificateOnErrorView,
                                mSSLCertificateOnErrorHandler,
                                mSSLCertificateOnErrorError);
                        } else {
                            // otherwise, display the top-most certificate from
                            // the chain
                            if (view.getCertificate() != null) {
                                showSSLCertificate(tab);
                            }
                        }
                    }
                });
        }

        mPageInfoDialog = alertDialogBuilder.show();
    }

       /**
     * Displays the main top-level page SSL certificate dialog
     * (accessible from the Page-Info dialog).
     * @param tab The tab to show certificate for.
     */
    private void showSSLCertificate(final Tab tab) {
        final View certificateView =
                inflateCertificateView(tab.getWebView().getCertificate());
        if (certificateView == null) {
            return;
        }

        LayoutInflater factory = LayoutInflater.from(this);

        final LinearLayout placeholder =
                (LinearLayout)certificateView.findViewById(R.id.placeholder);

        LinearLayout ll = (LinearLayout) factory.inflate(
            R.layout.ssl_success, placeholder);
        ((TextView)ll.findViewById(R.id.success))
            .setText(R.string.ssl_certificate_is_valid);

        mSSLCertificateView = tab;
        mSSLCertificateDialog =
            new AlertDialog.Builder(this)
                .setTitle(R.string.ssl_certificate).setIcon(
                    R.drawable.ic_dialog_browser_certificate_secure)
                .setView(certificateView)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                                mSSLCertificateDialog = null;
                                mSSLCertificateView = null;

                                showPageInfo(tab, false);
                            }
                        })
                .setOnCancelListener(
                        new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface dialog) {
                                mSSLCertificateDialog = null;
                                mSSLCertificateView = null;

                                showPageInfo(tab, false);
                            }
                        })
                .show();
    }

    /**
     * Displays the SSL error certificate dialog.
     * @param view The target web-view.
     * @param handler The SSL error handler responsible for cancelling the
     * connection that resulted in an SSL error or proceeding per user request.
     * @param error The SSL error object.
     */
    void showSSLCertificateOnError(
        final WebView view, final SslErrorHandler handler, final SslError error) {

        final View certificateView =
            inflateCertificateView(error.getCertificate());
        if (certificateView == null) {
            return;
        }

        LayoutInflater factory = LayoutInflater.from(this);

        final LinearLayout placeholder =
                (LinearLayout)certificateView.findViewById(R.id.placeholder);

        if (error.hasError(SslError.SSL_UNTRUSTED)) {
            LinearLayout ll = (LinearLayout)factory
                .inflate(R.layout.ssl_warning, placeholder);
            ((TextView)ll.findViewById(R.id.warning))
                .setText(R.string.ssl_untrusted);
        }

        if (error.hasError(SslError.SSL_IDMISMATCH)) {
            LinearLayout ll = (LinearLayout)factory
                .inflate(R.layout.ssl_warning, placeholder);
            ((TextView)ll.findViewById(R.id.warning))
                .setText(R.string.ssl_mismatch);
        }

        if (error.hasError(SslError.SSL_EXPIRED)) {
            LinearLayout ll = (LinearLayout)factory
                .inflate(R.layout.ssl_warning, placeholder);
            ((TextView)ll.findViewById(R.id.warning))
                .setText(R.string.ssl_expired);
        }

        if (error.hasError(SslError.SSL_NOTYETVALID)) {
            LinearLayout ll = (LinearLayout)factory
                .inflate(R.layout.ssl_warning, placeholder);
            ((TextView)ll.findViewById(R.id.warning))
                .setText(R.string.ssl_not_yet_valid);
        }

        mSSLCertificateOnErrorHandler = handler;
        mSSLCertificateOnErrorView = view;
        mSSLCertificateOnErrorError = error;
        mSSLCertificateOnErrorDialog =
            new AlertDialog.Builder(this)
                .setTitle(R.string.ssl_certificate).setIcon(
                    R.drawable.ic_dialog_browser_certificate_partially_secure)
                .setView(certificateView)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                                mSSLCertificateOnErrorDialog = null;
                                mSSLCertificateOnErrorView = null;
                                mSSLCertificateOnErrorHandler = null;
                                mSSLCertificateOnErrorError = null;

                                view.getWebViewClient().onReceivedSslError(
                                                view, handler, error);
                            }
                        })
                 .setNeutralButton(R.string.page_info_view,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                                mSSLCertificateOnErrorDialog = null;

                                // do not clear the dialog state: we will
                                // need to show the dialog again once the
                                // user is done exploring the page-info details

                                showPageInfo(mTabControl.getTabFromView(view),
                                        true);
                            }
                        })
                .setOnCancelListener(
                        new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface dialog) {
                                mSSLCertificateOnErrorDialog = null;
                                mSSLCertificateOnErrorView = null;
                                mSSLCertificateOnErrorHandler = null;
                                mSSLCertificateOnErrorError = null;

                                view.getWebViewClient().onReceivedSslError(
                                                view, handler, error);
                            }
                        })
                .show();
    }

    /**
     * Inflates the SSL certificate view (helper method).
     * @param certificate The SSL certificate.
     * @return The resultant certificate view with issued-to, issued-by,
     * issued-on, expires-on, and possibly other fields set.
     * If the input certificate is null, returns null.
     */
    private View inflateCertificateView(SslCertificate certificate) {
        if (certificate == null) {
            return null;
        }

        LayoutInflater factory = LayoutInflater.from(this);

        View certificateView = factory.inflate(
            R.layout.ssl_certificate, null);

        // issued to:
        SslCertificate.DName issuedTo = certificate.getIssuedTo();
        if (issuedTo != null) {
            ((TextView) certificateView.findViewById(R.id.to_common))
                .setText(issuedTo.getCName());
            ((TextView) certificateView.findViewById(R.id.to_org))
                .setText(issuedTo.getOName());
            ((TextView) certificateView.findViewById(R.id.to_org_unit))
                .setText(issuedTo.getUName());
        }

        // issued by:
        SslCertificate.DName issuedBy = certificate.getIssuedBy();
        if (issuedBy != null) {
            ((TextView) certificateView.findViewById(R.id.by_common))
                .setText(issuedBy.getCName());
            ((TextView) certificateView.findViewById(R.id.by_org))
                .setText(issuedBy.getOName());
            ((TextView) certificateView.findViewById(R.id.by_org_unit))
                .setText(issuedBy.getUName());
        }

        // issued on:
        String issuedOn = formatCertificateDate(
            certificate.getValidNotBeforeDate());
        ((TextView) certificateView.findViewById(R.id.issued_on))
            .setText(issuedOn);

        // expires on:
        String expiresOn = formatCertificateDate(
            certificate.getValidNotAfterDate());
        ((TextView) certificateView.findViewById(R.id.expires_on))
            .setText(expiresOn);

        return certificateView;
    }

    /**
     * Formats the certificate date to a properly localized date string.
     * @return Properly localized version of the certificate date string and
     * the "" if it fails to localize.
     */
    private String formatCertificateDate(Date certificateDate) {
      if (certificateDate == null) {
          return "";
      }
      String formattedDate = DateFormat.getDateFormat(this).format(certificateDate);
      if (formattedDate == null) {
          return "";
      }
      return formattedDate;
    }

    /**
     * Displays an http-authentication dialog.
     */
    void showHttpAuthentication(final HttpAuthHandler handler,
            final String host, final String realm, final String title,
            final String name, final String password, int focusId) {
        LayoutInflater factory = LayoutInflater.from(this);
        final View v = factory
                .inflate(R.layout.http_authentication, null);
        if (name != null) {
            ((EditText) v.findViewById(R.id.username_edit)).setText(name);
        }
        if (password != null) {
            ((EditText) v.findViewById(R.id.password_edit)).setText(password);
        }

        String titleText = title;
        if (titleText == null) {
            titleText = getText(R.string.sign_in_to).toString().replace(
                    "%s1", host).replace("%s2", realm);
        }

        mHttpAuthHandler = handler;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleText)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setView(v)
                .setPositiveButton(R.string.action,
                        new DialogInterface.OnClickListener() {
                             public void onClick(DialogInterface dialog,
                                     int whichButton) {
                                String nm = ((EditText) v
                                        .findViewById(R.id.username_edit))
                                        .getText().toString();
                                String pw = ((EditText) v
                                        .findViewById(R.id.password_edit))
                                        .getText().toString();
                                BrowserActivity.this.setHttpAuthUsernamePassword
                                        (host, realm, nm, pw);
                                handler.proceed(nm, pw);
                                mHttpAuthenticationDialog = null;
                                mHttpAuthHandler = null;
                            }})
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                                handler.cancel();
                                BrowserActivity.this.resetTitleAndRevertLockIcon();
                                mHttpAuthenticationDialog = null;
                                mHttpAuthHandler = null;
                            }})
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface dialog) {
                            handler.cancel();
                            BrowserActivity.this.resetTitleAndRevertLockIcon();
                            mHttpAuthenticationDialog = null;
                            mHttpAuthHandler = null;
                        }})
                .create();
        // Make the IME appear when the dialog is displayed if applicable.
        dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        if (focusId != 0) {
            dialog.findViewById(focusId).requestFocus();
        } else {
            v.findViewById(R.id.username_edit).requestFocus();
        }
        mHttpAuthenticationDialog = dialog;
    }

    public int getProgress() {
        WebView w = mTabControl.getCurrentWebView();
        if (w != null) {
            return w.getProgress();
        } else {
            return 100;
        }
    }

    /**
     * Set HTTP authentication password.
     *
     * @param host The host for the password
     * @param realm The realm for the password
     * @param username The username for the password. If it is null, it means
     *            password can't be saved.
     * @param password The password
     */
    public void setHttpAuthUsernamePassword(String host, String realm,
                                            String username,
                                            String password) {
        WebView w = getTopWindow();
        if (w != null) {
            w.setHttpAuthUsernamePassword(host, realm, username, password);
        }
    }

    /**
     * connectivity manager says net has come or gone... inform the user
     * @param up true if net has come up, false if net has gone down
     */
    public void onNetworkToggle(boolean up) {
        if (up == mIsNetworkUp) {
            return;
        } else if (up) {
            mIsNetworkUp = true;
            if (mAlertDialog != null) {
                mAlertDialog.cancel();
                mAlertDialog = null;
            }
        } else {
            mIsNetworkUp = false;
            if (mInLoad) {
                createAndShowNetworkDialog();
           }
        }
        WebView w = mTabControl.getCurrentWebView();
        if (w != null) {
            w.setNetworkAvailable(up);
        }
    }

    boolean isNetworkUp() {
        return mIsNetworkUp;
    }

    // This method shows the network dialog alerting the user that the net is
    // down. It will only show the dialog if mAlertDialog is null.
    private void createAndShowNetworkDialog() {
        if (mAlertDialog == null) {
            mAlertDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.loadSuspendedTitle)
                    .setMessage(R.string.loadSuspended)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent intent) {
        if (getTopWindow() == null) return;

        switch (requestCode) {
            case COMBO_PAGE:
                if (resultCode == RESULT_OK && intent != null) {
                    String data = intent.getAction();
                    Bundle extras = intent.getExtras();
                    if (extras != null &&
                            extras.getBoolean(
                                    CombinedBookmarkHistoryActivity.EXTRA_OPEN_NEW_WINDOW,
                                    false)) {
                        openTab(data, false);
                    } else if ((extras != null) &&
                            extras.getBoolean(CombinedBookmarkHistoryActivity.NEWTAB_MODE)) {
                        openTab(data, true);
                    } else {
                        final Tab currentTab = mTabControl.getCurrentTab();
                        dismissSubWindow(currentTab);
                        if (data != null && data.length() != 0) {
                            loadUrl(getTopWindow(), data);
                        }
                    }
                } else if (resultCode == RESULT_CANCELED) {
                    if (intent != null) {
                        float evtx = intent.getFloatExtra(CombinedBookmarkHistoryActivity.EVT_X, -1);
                        float evty = intent.getFloatExtra(CombinedBookmarkHistoryActivity.EVT_Y, -1);
                        long now = System.currentTimeMillis();
                        MotionEvent evt = MotionEvent.obtain(now, now,
                                MotionEvent.ACTION_DOWN, evtx, evty, 0);
                        dispatchTouchEvent(evt);
                        MotionEvent up = MotionEvent.obtain(evt);
                        up.setAction(MotionEvent.ACTION_UP);
                        dispatchTouchEvent(up);
                    }
                }
                // Deliberately fall through to PREFERENCES_PAGE, since the
                // same extra may be attached to the COMBO_PAGE
            case PREFERENCES_PAGE:
                if (resultCode == RESULT_OK && intent != null) {
                    String action = intent.getStringExtra(Intent.EXTRA_TEXT);
                    if (BrowserSettings.PREF_CLEAR_HISTORY.equals(action)) {
                        mTabControl.removeParentChildRelationShips();
                    }
                }
                break;
            // Choose a file from the file picker.
            case FILE_SELECTED:
                if (null == mUploadMessage) break;
                Uri result = intent == null || resultCode != RESULT_OK ? null
                        : intent.getData();

                // As we ask the camera to save the result of the user taking
                // a picture, the camera application does not return anything other
                // than RESULT_OK. So we need to check whether the file we expected
                // was written to disk in the in the case that we
                // did not get an intent returned but did get a RESULT_OK. If it was,
                // we assume that this result has came back from the camera.
                if (result == null && intent == null && resultCode == RESULT_OK) {
                    File cameraFile = new File(mCameraFilePath);
                    if (cameraFile.exists()) {
                        result = Uri.fromFile(cameraFile);
                        // Broadcast to the media scanner that we have a new photo
                        // so it will be added into the gallery for the user.
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, result));
                    }
                }
                mUploadMessage.onReceiveValue(result);
                mUploadMessage = null;
                mCameraFilePath = null;
                break;
            default:
                break;
        }
        getTopWindow().requestFocus();
    }

    /*
     * This method is called as a result of the user selecting the options
     * menu to see the download window. It shows the download window on top of
     * the current window.
     */
    private void viewDownloads() {
        Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        startActivity(intent);
    }

    /* package */ void promptAddOrInstallBookmark(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.bookmark_shortcut, popup.getMenu());
        popup.setOnMenuItemClickListener(this);
        popup.show();
    }
    
    /**
     * popup menu item click listener
     * @param item
     */
    public boolean onMenuItemClick(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.add_bookmark_menu_id:
                bookmarkCurrentPage();
                return true;
            case R.id.shortcut_to_home_menu_id:
                Tab current = mTabControl.getCurrentTab();
                current.populatePickerData();
                String touchIconUrl = mTabControl.getCurrentWebView().getTouchIconUrl();
                if (touchIconUrl != null) {
                    // Download the touch icon for this site then save
                    // it to the
                    // homescreen.
                    Bundle b = new Bundle();
                    b.putString("url", current.getUrl());
                    b.putString("title", current.getTitle());
                    b.putParcelable("favicon", current.getFavicon());
                    Message msg = mHandler.obtainMessage(TOUCH_ICON_DOWNLOADED);
                    msg.setData(b);
                    new DownloadTouchIcon(BrowserActivity.this, msg,
                            mTabControl.getCurrentWebView().getSettings().getUserAgentString())
                            .execute(touchIconUrl);
                } else {
                    // add to homescreen, can do it immediately as there
                    // is no touch
                    // icon.
                    showSaveToHomescreenDialog(
                            current.getUrl(), current.getTitle(), null, current.getFavicon());
                }
                return true;
            default:
                return false;
        }
    }
    
    /* package */Dialog makeAddOrInstallDialog() {
        final Tab current = mTabControl.getCurrentTab();
        Resources resources = getResources();
        CharSequence[] choices =
                {resources.getString(R.string.save_to_bookmarks),
                        resources.getString(R.string.create_shortcut_bookmark)};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.add_new_bookmark);
        builder.setItems(choices, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
                if (item == 0) {
                    bookmarkCurrentPage();
                } else if (item == 1) {
                }
            }
        });
        return builder.create();
    }

    /**
     * Open the Go page.
     * @param startWithHistory If true, open starting on the history tab.
     *                         Otherwise, start with the bookmarks tab.
     */
    /* package */ void bookmarksOrHistoryPicker(boolean startWithHistory, boolean newTabMode) {
        WebView current = mTabControl.getCurrentWebView();
        if (current == null) {
            return;
        }
        Intent intent = new Intent(this,
                CombinedBookmarkHistoryActivity.class);
        String title = current.getTitle();
        String url = current.getUrl();
        Bitmap thumbnail = createScreenshot(current, getDesiredThumbnailWidth(this),
                getDesiredThumbnailHeight(this));

        // Just in case the user opens bookmarks before a page finishes loading
        // so the current history item, and therefore the page, is null.
        if (null == url) {
            url = mLastEnteredUrl;
            // This can happen.
            if (null == url) {
                url = mSettings.getHomePage();
            }
        }
        // In case the web page has not yet received its associated title.
        if (title == null) {
            title = url;
        }
        intent.putExtra("title", title);
        intent.putExtra("url", url);
        intent.putExtra("thumbnail", thumbnail);
        // Disable opening in a new window if we have maxed out the windows
        intent.putExtra("disable_new_window", !mTabControl.canCreateNewTab());
        intent.putExtra("touch_icon_url", current.getTouchIconUrl());
        if (startWithHistory) {
            intent.putExtra(CombinedBookmarkHistoryActivity.STARTING_FRAGMENT,
                    CombinedBookmarkHistoryActivity.FRAGMENT_ID_HISTORY);
        }
        intent.putExtra(CombinedBookmarkHistoryActivity.NEWTAB_MODE, newTabMode);
        int top = -1;
        int height = -1;
        if (mXLargeScreenSize) {
            showFakeTitleBar();
            int titleBarHeight = ((TitleBarXLarge)mFakeTitleBar).getHeightWithoutProgress();
            top = mTabBar.getBottom() + titleBarHeight;
            height = getTopWindow().getHeight() - titleBarHeight;
        }
        intent.putExtra(CombinedBookmarkHistoryActivity.EXTRA_TOP, top);
        intent.putExtra(CombinedBookmarkHistoryActivity.EXTRA_HEIGHT, height);
        startActivityForResult(intent, COMBO_PAGE);
    }

    private void showSaveToHomescreenDialog(String url, String title, Bitmap touchIcon,
            Bitmap favicon) {
        Intent intent = new Intent(this, SaveToHomescreenDialog.class);

        // Just in case the user tries to save before a page finishes loading
        // so the current history item, and therefore the page, is null.
        if (null == url) {
            url = mLastEnteredUrl;
            // This can happen.
            if (null == url) {
                url = mSettings.getHomePage();
            }
        }

        // In case the web page has not yet received its associated title.
        if (title == null) {
            title = url;
        }

        intent.putExtra("title", title);
        intent.putExtra("url", url);
        intent.putExtra("favicon", favicon);
        intent.putExtra("touchIcon", touchIcon);
        startActivity(intent);
    }


    // Called when loading from context menu or LOAD_URL message
    private void loadUrlFromContext(WebView view, String url) {
        // In case the user enters nothing.
        if (url != null && url.length() != 0 && view != null) {
            url = smartUrlFilter(url);
            if (!view.getWebViewClient().shouldOverrideUrlLoading(view, url)) {
                loadUrl(view, url);
            }
        }
    }

    /**
     * Load the URL into the given WebView and update the title bar
     * to reflect the new load.  Call this instead of WebView.loadUrl
     * directly.
     * @param view The WebView used to load url.
     * @param url The URL to load.
     */
    private void loadUrl(WebView view, String url) {
        updateTitleBarForNewLoad(view, url);
        view.loadUrl(url);
    }

    /**
     * Load UrlData into a Tab and update the title bar to reflect the new
     * load.  Call this instead of UrlData.loadIn directly.
     * @param t The Tab used to load.
     * @param data The UrlData being loaded.
     */
    private void loadUrlDataIn(Tab t, UrlData data) {
        updateTitleBarForNewLoad(t.getWebView(), data.mUrl);
        data.loadIn(t);
    }

    /**
     * If the WebView is the top window, update the title bar to reflect
     * loading the new URL.  i.e. set its text, clear the favicon (which
     * will be set once the page begins loading), and set the progress to
     * INITIAL_PROGRESS to show that the page has begun to load. Called
     * by loadUrl and loadUrlDataIn.
     * @param view The WebView that is starting a load.
     * @param url The URL that is being loaded.
     */
    private void updateTitleBarForNewLoad(WebView view, String url) {
        if (view == getTopWindow()) {
            setUrlTitle(url, null);
            setFavicon(null);
            onProgressChanged(view, INITIAL_PROGRESS);
        }
    }

    private String smartUrlFilter(Uri inUri) {
        if (inUri != null) {
            return smartUrlFilter(inUri.toString());
        }
        return null;
    }

    protected static final Pattern ACCEPTED_URI_SCHEMA = Pattern.compile(
            "(?i)" + // switch on case insensitive matching
            "(" +    // begin group for schema
            "(?:http|https|file):\\/\\/" +
            "|(?:inline|data|about|content|javascript):" +
            ")" +
            "(.*)" );

    /**
     * Attempts to determine whether user input is a URL or search
     * terms.  Anything with a space is passed to search.
     *
     * Converts to lowercase any mistakenly uppercased schema (i.e.,
     * "Http://" converts to "http://"
     *
     * @return Original or modified URL
     *
     */
    String smartUrlFilter(String url) {

        String inUrl = url.trim();
        boolean hasSpace = inUrl.indexOf(' ') != -1;

        Matcher matcher = ACCEPTED_URI_SCHEMA.matcher(inUrl);
        if (matcher.matches()) {
            // force scheme to lowercase
            String scheme = matcher.group(1);
            String lcScheme = scheme.toLowerCase();
            if (!lcScheme.equals(scheme)) {
                inUrl = lcScheme + matcher.group(2);
            }
            if (hasSpace) {
                inUrl = inUrl.replace(" ", "%20");
            }
            return inUrl;
        }
        if (hasSpace) {
            // FIXME: Is this the correct place to add to searches?
            // what if someone else calls this function?
            int shortcut = parseUrlShortcut(inUrl);
            if (shortcut != SHORTCUT_INVALID) {
                Browser.addSearchUrl(mResolver, inUrl);
                String query = inUrl.substring(2);
                switch (shortcut) {
                case SHORTCUT_GOOGLE_SEARCH:
                    return URLUtil.composeSearchUrl(query, QuickSearch_G, QUERY_PLACE_HOLDER);
                case SHORTCUT_WIKIPEDIA_SEARCH:
                    return URLUtil.composeSearchUrl(query, QuickSearch_W, QUERY_PLACE_HOLDER);
                case SHORTCUT_DICTIONARY_SEARCH:
                    return URLUtil.composeSearchUrl(query, QuickSearch_D, QUERY_PLACE_HOLDER);
                case SHORTCUT_GOOGLE_MOBILE_LOCAL_SEARCH:
                    // FIXME: we need location in this case
                    return URLUtil.composeSearchUrl(query, QuickSearch_L, QUERY_PLACE_HOLDER);
                }
            }
        } else {
            if (Patterns.WEB_URL.matcher(inUrl).matches()) {
                return URLUtil.guessUrl(inUrl);
            }
        }

        Browser.addSearchUrl(mResolver, inUrl);
        return URLUtil.composeSearchUrl(inUrl, QuickSearch_G, QUERY_PLACE_HOLDER);
    }

    /* package */ void setShouldShowErrorConsole(boolean flag) {
        if (flag == mShouldShowErrorConsole) {
            // Nothing to do.
            return;
        }
        Tab t = mTabControl.getCurrentTab();
        if (t == null) {
            // There is no current tab so we cannot toggle the error console
            return;
        }

        mShouldShowErrorConsole = flag;

        ErrorConsoleView errorConsole = t.getErrorConsole(true);

        if (flag) {
            // Setting the show state of the console will cause it's the layout to be inflated.
            if (errorConsole.numberOfErrors() > 0) {
                errorConsole.showConsole(ErrorConsoleView.SHOW_MINIMIZED);
            } else {
                errorConsole.showConsole(ErrorConsoleView.SHOW_NONE);
            }

            // Now we can add it to the main view.
            mErrorConsoleContainer.addView(errorConsole,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                  ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            mErrorConsoleContainer.removeView(errorConsole);
        }

    }

    boolean shouldShowErrorConsole() {
        return mShouldShowErrorConsole;
    }

    private void setStatusBarVisibility(boolean visible) {
        int flag = visible ? 0 : WindowManager.LayoutParams.FLAG_FULLSCREEN;
        getWindow().setFlags(flag, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }


    private void sendNetworkType(String type, String subtype) {
        WebView w = mTabControl.getCurrentWebView();
        if (w != null) {
            w.setNetworkType(type, subtype);
        }
    }

    final static int LOCK_ICON_UNSECURE = 0;
    final static int LOCK_ICON_SECURE   = 1;
    final static int LOCK_ICON_MIXED    = 2;

    private BrowserSettings mSettings;
    private TabControl      mTabControl;
    private ContentResolver mResolver;
    private FrameLayout     mContentView;
    private View            mCustomView;
    private FrameLayout     mCustomViewContainer;
    private WebChromeClient.CustomViewCallback mCustomViewCallback;

    // FIXME, temp address onPrepareMenu performance problem. When we move everything out of
    // view, we should rewrite this.
    private int mCurrentMenuState = 0;
    private int mMenuState = R.id.MAIN_MENU;
    private int mOldMenuState = EMPTY_MENU;
    private static final int EMPTY_MENU = -1;
    private Menu mMenu;

    // Used to prevent chording to result in firing two shortcuts immediately
    // one after another.  Fixes bug 1211714.
    boolean mCanChord;

    private boolean mInLoad;
    private boolean mIsNetworkUp;
    private boolean mDidStopLoad;

    /* package */ boolean mActivityInPause = true;

    private boolean mMenuIsDown;

    private static boolean mInTrace;

    // Performance probe
    private static final int[] SYSTEM_CPU_FORMAT = new int[] {
            Process.PROC_SPACE_TERM | Process.PROC_COMBINE,
            Process.PROC_SPACE_TERM | Process.PROC_OUT_LONG, // 1: user time
            Process.PROC_SPACE_TERM | Process.PROC_OUT_LONG, // 2: nice time
            Process.PROC_SPACE_TERM | Process.PROC_OUT_LONG, // 3: sys time
            Process.PROC_SPACE_TERM | Process.PROC_OUT_LONG, // 4: idle time
            Process.PROC_SPACE_TERM | Process.PROC_OUT_LONG, // 5: iowait time
            Process.PROC_SPACE_TERM | Process.PROC_OUT_LONG, // 6: irq time
            Process.PROC_SPACE_TERM | Process.PROC_OUT_LONG  // 7: softirq time
    };

    private long mStart;
    private long mProcessStart;
    private long mUserStart;
    private long mSystemStart;
    private long mIdleStart;
    private long mIrqStart;

    private long mUiStart;

    private Drawable    mMixLockIcon;
    private Drawable    mSecLockIcon;

    /* hold a ref so we can auto-cancel if necessary */
    private AlertDialog mAlertDialog;

    // The up-to-date URL and title (these can be different from those stored
    // in WebView, since it takes some time for the information in WebView to
    // get updated)
    private String mUrl;
    private String mTitle;

    // As PageInfo has different style for landscape / portrait, we have
    // to re-open it when configuration changed
    private AlertDialog mPageInfoDialog;
    private Tab mPageInfoView;
    // If the Page-Info dialog is launched from the SSL-certificate-on-error
    // dialog, we should not just dismiss it, but should get back to the
    // SSL-certificate-on-error dialog. This flag is used to store this state
    private boolean mPageInfoFromShowSSLCertificateOnError;

    // as SSLCertificateOnError has different style for landscape / portrait,
    // we have to re-open it when configuration changed
    private AlertDialog mSSLCertificateOnErrorDialog;
    private WebView mSSLCertificateOnErrorView;
    private SslErrorHandler mSSLCertificateOnErrorHandler;
    private SslError mSSLCertificateOnErrorError;

    // as SSLCertificate has different style for landscape / portrait, we
    // have to re-open it when configuration changed
    private AlertDialog mSSLCertificateDialog;
    private Tab mSSLCertificateView;

    // as HttpAuthentication has different style for landscape / portrait, we
    // have to re-open it when configuration changed
    private AlertDialog mHttpAuthenticationDialog;
    private HttpAuthHandler mHttpAuthHandler;

    /*package*/ static final FrameLayout.LayoutParams COVER_SCREEN_PARAMS =
                                            new FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT);
    /*package*/ static final FrameLayout.LayoutParams COVER_SCREEN_GRAVITY_CENTER =
                                            new FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            Gravity.CENTER);
    // Google search
    final static String QuickSearch_G = "http://www.google.com/m?q=%s";
    // Wikipedia search
    final static String QuickSearch_W = "http://en.wikipedia.org/w/index.php?search=%s&go=Go";
    // Dictionary search
    final static String QuickSearch_D = "http://dictionary.reference.com/search?q=%s";
    // Google Mobile Local search
    final static String QuickSearch_L = "http://www.google.com/m/search?site=local&q=%s&near=mountain+view";

    final static String QUERY_PLACE_HOLDER = "%s";

    // "source" parameter for Google search through search key
    final static String GOOGLE_SEARCH_SOURCE_SEARCHKEY = "browser-key";
    // "source" parameter for Google search through goto menu
    final static String GOOGLE_SEARCH_SOURCE_GOTO = "browser-goto";
    // "source" parameter for Google search through simplily type
    final static String GOOGLE_SEARCH_SOURCE_TYPE = "browser-type";
    // "source" parameter for Google search suggested by the browser
    final static String GOOGLE_SEARCH_SOURCE_SUGGEST = "browser-suggest";
    // "source" parameter for Google search from unknown source
    final static String GOOGLE_SEARCH_SOURCE_UNKNOWN = "unknown";

    private final static String LOGTAG = "browser";

    private String mLastEnteredUrl;

    private PowerManager.WakeLock mWakeLock;
    private final static int WAKELOCK_TIMEOUT = 5 * 60 * 1000; // 5 minutes

    private Toast mStopToast;

    private TitleBarBase mTitleBar;
    private TabBar mTabBar;

    private LinearLayout mErrorConsoleContainer = null;
    private boolean mShouldShowErrorConsole = false;

    // As the ids are dynamically created, we can't guarantee that they will
    // be in sequence, so this static array maps ids to a window number.
    final static private int[] WINDOW_SHORTCUT_ID_ARRAY =
    { R.id.window_one_menu_id, R.id.window_two_menu_id, R.id.window_three_menu_id,
      R.id.window_four_menu_id, R.id.window_five_menu_id, R.id.window_six_menu_id,
      R.id.window_seven_menu_id, R.id.window_eight_menu_id };

    // monitor platform changes
    private IntentFilter mNetworkStateChangedFilter;
    private BroadcastReceiver mNetworkStateIntentReceiver;

    private SystemAllowGeolocationOrigins mSystemAllowGeolocationOrigins;

    // activity requestCode
    final static int COMBO_PAGE                 = 1;
    final static int PREFERENCES_PAGE           = 3;
    final static int FILE_SELECTED              = 4;

    // the default <video> poster
    private Bitmap mDefaultVideoPoster;
    // the video progress view
    private View mVideoProgressView;

    /**
     * A UrlData class to abstract how the content will be set to WebView.
     * This base class uses loadUrl to show the content.
     */
    /* package */ static class UrlData {
        final String mUrl;
        final Map<String, String> mHeaders;
        final Intent mVoiceIntent;

        UrlData(String url) {
            this.mUrl = url;
            this.mHeaders = null;
            this.mVoiceIntent = null;
        }

        UrlData(String url, Map<String, String> headers, Intent intent) {
            this.mUrl = url;
            this.mHeaders = headers;
            if (RecognizerResultsIntent.ACTION_VOICE_SEARCH_RESULTS
                    .equals(intent.getAction())) {
                this.mVoiceIntent = intent;
            } else {
                this.mVoiceIntent = null;
            }
        }

        boolean isEmpty() {
            return mVoiceIntent == null && (mUrl == null || mUrl.length() == 0);
        }

        /**
         * Load this UrlData into the given Tab.  Use loadUrlDataIn to update
         * the title bar as well.
         */
        public void loadIn(Tab t) {
            if (mVoiceIntent != null) {
                t.activateVoiceSearchMode(mVoiceIntent);
            } else {
                t.getWebView().loadUrl(mUrl, mHeaders);
            }
        }
    };

    /* package */ static final UrlData EMPTY_URL_DATA = new UrlData(null);
}
