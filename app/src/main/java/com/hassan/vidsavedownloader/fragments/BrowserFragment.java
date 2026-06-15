package com.hassan.vidsavedownloader.fragments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.hassan.vidsavedownloader.R;
import com.hassan.vidsavedownloader.helpers.VideoExtractorHelper.VideoEntry;
import com.hassan.vidsavedownloader.utils.UrlResolver;
import com.hassan.vidsavedownloader.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class BrowserFragment extends Fragment {

    private static final String ARG_URL = "initial_url";
    private static final String TAG = "BrowserFragment";

    /**
     * Extract visible video links from the rendered DOM (includes JS-loaded content).
     */
    private static final String JS_EXTRACT_VIDEOS =
            "(function(){"
            + "var out=[],seen={},pageTitle=document.title||'';"
            + "function add(u,t,th){"
            + " if(!u||u.indexOf('blob:')===0||u.indexOf('javascript:')===0)return;"
            + " try{var abs=new URL(u,location.href).href;"
            + " var k=abs.split('#')[0].split('?')[0];"
            + " if(seen[k])return; seen[k]=1;"
            + " out.push({url:abs,title:(t||'').trim().substring(0,200),"
            + " thumb:(th||'').trim(),duration:0});"
            + " }catch(e){}"
            + "}"
            + "document.querySelectorAll('video').forEach(function(v){"
            + " if(v.src)add(v.src,pageTitle,v.poster||'');"
            + " v.querySelectorAll('source').forEach(function(s){if(s.src)add(s.src,pageTitle,v.poster||'');});"
            + "});"
            + "document.querySelectorAll('a[href]').forEach(function(a){"
            + " var h=a.getAttribute('href')||'';"
            + " if(!h||h.charAt(0)==='#')return;"
            + " var txt=(a.innerText||a.getAttribute('aria-label')||a.title||'').trim();"
            + " var img=a.querySelector('img');"
            + " var th=img?(img.currentSrc||img.src||img.getAttribute('data-src')||''):'';"
            + " var hl=h.toLowerCase();"
            + " var ok=/video|watch|reel|shorts|clip|\\/v\\/|\\.mp4|\\.m3u8|\\.webm|embed|player|pin\\.it|youtu|tiktok|instagram|facebook|twitter|vimeo|dailymotion/i;"
            + " var inBox=a.closest('[class*=\"video\"],[class*=\"reel\"],[class*=\"clip\"],[class*=\"player\"],[class*=\"thumb\"]');"
            + " if(ok.test(h)||ok.test(txt)||inBox)add(h,txt,th);"
            + "});"
            + "var ogv=document.querySelector('meta[property=\"og:video\"],meta[property=\"og:video:url\"]');"
            + "if(ogv&&ogv.content)add(ogv.content,pageTitle,'');"
            + "var ogImg=document.querySelector('meta[property=\"og:image\"]');"
            + "var ogThumb=ogImg&&ogImg.content?ogImg.content:'';"
            + "add(location.href,pageTitle,ogThumb);"
            + "return JSON.stringify(out);"
            + "})();";

    private static final String JS_GET_HTML =
            "(function(){"
            + "try{return document.documentElement.outerHTML.substring(0,250000);}"
            + "catch(e){return '';}"
            + "})();";

    private WebView webView;
    private ProgressBar progressBar;
    private ImageView btnBack, btnForward;

    public static BrowserFragment newInstance(String url) {
        BrowserFragment f = new BrowserFragment();
        Bundle args = new Bundle();
        args.putString(ARG_URL, (url != null && !url.trim().isEmpty())
                ? url : "https://www.google.com");
        f.setArguments(args);
        return f;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_browser, container, false);

        webView     = view.findViewById(R.id.webView);
        progressBar = view.findViewById(R.id.webProgressBar);
        btnBack     = view.findViewById(R.id.btnBack);
        btnForward  = view.findViewById(R.id.btnForward);

        ImageView btnClose   = view.findViewById(R.id.btnClose);
        ImageView btnRefresh = view.findViewById(R.id.btnRefresh);
        ExtendedFloatingActionButton fabDownload = view.findViewById(R.id.fabDownload);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView w, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView w, String url, Bitmap favicon) {
                updateNavButtons();
            }

            @Override
            public void onPageFinished(WebView w, String url) {
                updateNavButtons();
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest request) {
                return handleNavigation(w, request != null ? request.getUrl() : null);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView w, String url) {
                return handleNavigation(w, url != null ? Uri.parse(url) : null);
            }
        });

        btnClose.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());

        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });

        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });

        btnRefresh.setOnClickListener(v -> webView.reload());

        fabDownload.setOnClickListener(v -> scanPageForVideos(fabDownload));

        String startUrl = getArguments() != null
                ? getArguments().getString(ARG_URL, "https://www.google.com")
                : "https://www.google.com";

        loadUrlInWebView(startUrl);

        return view;
    }

    private void loadUrlInWebView(String url) {
        if (webView == null || url == null || url.trim().isEmpty()) return;

        if (UrlResolver.needsExpansion(url)) {
            progressBar.setVisibility(View.VISIBLE);
            new Thread(() -> {
                String resolved = UrlResolver.resolve(url);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (UrlResolver.isWebViewLoadable(resolved)) {
                        webView.loadUrl(resolved);
                    } else {
                        Utils.showAnimatedToast(getActivity(),
                                "Could not open this link in the browser",
                                R.drawable.alert_error, Utils.ToastDuration.LONG);
                    }
                });
            }).start();
        } else if (UrlResolver.isWebViewLoadable(url)) {
            webView.loadUrl(url);
        } else {
            Utils.showAnimatedToast(getActivity(),
                    "Invalid browser URL",
                    R.drawable.alert_error, Utils.ToastDuration.SHORT);
        }
    }

    /** Only load http/https in WebView — block tiktok://, intent://, etc. */
    private boolean handleNavigation(WebView webView, Uri uri) {
        if (uri == null) return false;
        if (UrlResolver.isWebViewLoadable(uri)) {
            webView.loadUrl(uri.toString());
            return true;
        }
        Log.d(TAG, "Blocked non-http(s) navigation: " + uri);
        return true;
    }

    private void scanPageForVideos(ExtendedFloatingActionButton fab) {
        String currentUrl = webView.getUrl();
        if (currentUrl == null || currentUrl.isEmpty() || "about:blank".equals(currentUrl)) {
            Utils.showAnimatedToast(getActivity(),
                    "Navigate to a video page first",
                    R.drawable.alert_error, Utils.ToastDuration.SHORT);
            return;
        }

        fab.setText("Scanning page…");
        fab.setEnabled(false);

        String cookies   = CookieManager.getInstance().getCookie(currentUrl);
        String userAgent = webView.getSettings().getUserAgentString();

        AtomicInteger pending = new AtomicInteger(2);
        final String[] jsJsonHolder = { "[]" };
        final String[] htmlHolder   = { "" };

        Runnable tryCombined = () -> {
            if (pending.decrementAndGet() > 0) return;
            runCombinedScraper(currentUrl, cookies, userAgent, fab,
                    jsJsonHolder[0], htmlHolder[0]);
        };

        webView.evaluateJavascript(JS_EXTRACT_VIDEOS, value -> {
            jsJsonHolder[0] = unwrapJsString(value);
            tryCombined.run();
        });

        webView.evaluateJavascript(JS_GET_HTML, value -> {
            htmlHolder[0] = unwrapJsString(value);
            tryCombined.run();
        });
    }

    private void runCombinedScraper(String pageUrl,
                                    String cookies,
                                    String userAgent,
                                    ExtendedFloatingActionButton fab,
                                    String jsJson,
                                    String html) {
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(requireContext()));
                }

                Python py = Python.getInstance();
                PyObject module = py.getModule("page_scraper");
                PyObject result = module.callAttr(
                        "scrape_videos_combined",
                        pageUrl,
                        cookies != null ? cookies : "",
                        html != null ? html : "",
                        jsJson != null ? jsJson : "[]"
                );

                String json = result != null ? result.toString() : "[]";
                if (getActivity() == null) return;

                getActivity().runOnUiThread(() -> {
                    fab.setText("Download this video");
                    fab.setEnabled(true);
                    parseAndShowResults(json, cookies, userAgent, pageUrl);
                });

            } catch (Exception e) {
                Log.e(TAG, "Combined scraper failed", e);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    fab.setText("Download this video");
                    fab.setEnabled(true);
                    List<VideoEntry> fallback = new ArrayList<>();
                    fallback.add(new VideoEntry(pageUrl, "This page", "", 0));
                    showPicker(fallback, cookies, userAgent, pageUrl);
                });
            }
        }).start();
    }

    private void parseAndShowResults(String json,
                                     String cookies,
                                     String userAgent,
                                     String pageUrl) {
        List<VideoEntry> entries = new ArrayList<>();

        try {
            String trimmed = json.trim();

            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                if (obj.has("error")) {
                    Utils.showAnimatedToast(getActivity(),
                            obj.getString("error"),
                            R.drawable.alert_error, Utils.ToastDuration.LONG);
                    entries.add(new VideoEntry(pageUrl, "This page", "", 0));
                    showPicker(entries, cookies, userAgent, pageUrl);
                    return;
                }
            }

            JSONArray arr = new JSONArray(trimmed);
            Map<String, VideoEntry> seen = new LinkedHashMap<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String url   = obj.optString("url", "").trim();
                String title = obj.optString("title", "").trim();
                String thumb = obj.optString("thumb", "").trim();
                int    dur   = obj.optInt("duration", 0);

                if (url.isEmpty() || url.startsWith("blob:")) continue;

                String key = url.split("\\?")[0].split("#")[0];
                if (!seen.containsKey(key)) {
                    if (title.isEmpty()) title = "Video";
                    seen.put(key, new VideoEntry(url, title, thumb, dur));
                }
            }

            entries.addAll(seen.values());

        } catch (Exception e) {
            Log.e(TAG, "JSON parse error", e);
        }

        if (entries.isEmpty()) {
            entries.add(new VideoEntry(pageUrl, "This page", "", 0));
        } else {
            entries = sortVideoEntries(entries, pageUrl);
        }

        showPicker(entries, cookies, userAgent, pageUrl);
    }

    /** Page URL first, then other watch pages, then direct CDN files. */
    private List<VideoEntry> sortVideoEntries(List<VideoEntry> entries, String pageUrl) {
        List<VideoEntry> pageFirst   = new ArrayList<>();
        List<VideoEntry> watchPages  = new ArrayList<>();
        List<VideoEntry> directFiles = new ArrayList<>();

        for (VideoEntry entry : entries) {
            if (pageUrl != null && pageUrl.equals(entry.url)) {
                pageFirst.add(new VideoEntry(entry.url, "This page", entry.thumb, entry.durationSecs));
            } else if (UrlResolver.isDirectMediaUrl(entry.url)) {
                directFiles.add(entry);
            } else if (UrlResolver.isTikTokPageUrl(entry.url) || UrlResolver.isWebViewLoadable(entry.url)) {
                watchPages.add(entry);
            } else {
                watchPages.add(entry);
            }
        }

        List<VideoEntry> sorted = new ArrayList<>();
        sorted.addAll(pageFirst);
        sorted.addAll(watchPages);
        sorted.addAll(directFiles);
        return sorted;
    }

    private void showPicker(List<VideoEntry> entries, String cookies,
                            String userAgent, String pageReferer) {
        if (!isAdded() || getActivity() == null) return;

        VideoPickerBottomSheet sheet =
                VideoPickerBottomSheet.newInstance(entries, cookies, userAgent, pageReferer);
        sheet.show(requireActivity().getSupportFragmentManager(), "video_picker");
    }

    private static String unwrapJsString(String raw) {
        if (raw == null || "null".equals(raw)) return "";
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\u003C", "<")
                    .replace("\\u003E", ">")
                    .replace("\\\\", "\\");
        }
        return raw;
    }

    public boolean handleBackPress() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    private void updateNavButtons() {
        if (webView == null) return;
        btnBack.setAlpha(webView.canGoBack() ? 1f : 0.35f);
        btnForward.setAlpha(webView.canGoForward() ? 1f : 0.35f);
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroyView();
    }
}
