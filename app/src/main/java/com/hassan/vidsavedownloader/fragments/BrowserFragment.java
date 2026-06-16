package com.hassan.vidsavedownloader.fragments;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
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

    /** Extract videos from DOM, meta tags, and embedded JSON (TikTok, IG, etc.). */
    private static final String JS_EXTRACT_VIDEOS =
            "(function(){"
            + "var out=[],seen={},pageTitle=document.title||'';"
            + "function add(u,t,th){"
            + " if(!u||u.indexOf('blob:')===0||u.indexOf('javascript:')===0)return;"
            + " try{"
            + "  u=String(u).replace(/\\\\u002F/g,'/').replace(/\\\\\\//g,'/');"
            + "  var abs=new URL(u,location.href).href;"
            + "  var k=abs.split('#')[0].split('?')[0];"
            + "  if(seen[k])return; seen[k]=1;"
            + "  out.push({url:abs,title:(t||'').trim().substring(0,200),"
            + " thumb:(th||'').trim(),duration:0});"
            + " }catch(e){}"
            + "}"
            + "document.querySelectorAll('video').forEach(function(v){"
            + " var u=v.currentSrc||v.src||'';"
            + " if(u)add(u,pageTitle,v.poster||'');"
            + " v.querySelectorAll('source').forEach(function(s){"
            + "  var su=s.src||s.getAttribute('src')||''; if(su)add(su,pageTitle,v.poster||'');"
            + " });"
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
            + "var ogv=document.querySelector('meta[property=\"og:video\"],meta[property=\"og:video:url\"],meta[property=\"og:video:secure_url\"]');"
            + "if(ogv&&ogv.content)add(ogv.content,pageTitle,'');"
            + "var ogImg=document.querySelector('meta[property=\"og:image\"]');"
            + "var ogThumb=ogImg&&ogImg.content?ogImg.content:'';"
            + "try{"
            + " var html=document.documentElement.innerHTML||'';"
            + " var re=/https?:\\\\?\\/\\\\?\\/[^\"'\\s<>\\\\]+(?:mime_type=video|video\\\\?\\/tos|\\.mp4|\\.m3u8|playAddr|downloadAddr)[^\"'\\s<>\\\\]*/gi;"
            + " var m; while((m=re.exec(html))!==null){add(m[0],pageTitle,ogThumb);}"
            + " document.querySelectorAll('script[type=\"application/json\"],script[id*=\"SIGI\"],script[id*=\"__UNIVERSAL\"]').forEach(function(s){"
            + "  var t=s.textContent||'';"
            + "  if(t.length<50||t.length>500000)return;"
            + "  var um=t.match(/https?:\\\\\\/\\\\\\/[^\"\\\\]+(?:mime_type=video|video\\\\\\/tos|\\.mp4)[^\"\\\\]*/g);"
            + "  if(um)um.forEach(function(u){add(u.replace(/\\\\u002F/g,'/'),pageTitle,ogThumb);});"
            + " });"
            + "}catch(e){}"
            + "add(location.href,pageTitle,ogThumb);"
            + "return JSON.stringify(out);"
            + "})();";

    private static final String JS_GET_HTML =
            "(function(){"
            + "try{return document.documentElement.outerHTML.substring(0,350000);}"
            + "catch(e){return '';}"
            + "})();";

    private static final String JS_GET_DOCUMENT_COOKIE =
            "(function(){try{return document.cookie||'';}catch(e){return '';}})();";

    private WebView webView;
    private EditText browserUrlInput;
    private ProgressBar progressBar;
    private ImageView btnBack, btnForward;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean pageReady = false;

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

        webView          = view.findViewById(R.id.webView);
        browserUrlInput  = view.findViewById(R.id.browserUrlInput);
        progressBar      = view.findViewById(R.id.webProgressBar);
        btnBack          = view.findViewById(R.id.btnBack);
        btnForward       = view.findViewById(R.id.btnForward);

        ImageView btnClose   = view.findViewById(R.id.btnClose);
        ImageView btnRefresh = view.findViewById(R.id.btnRefresh);
        ImageView btnPaste   = view.findViewById(R.id.btnPasteUrl);
        ImageView btnGo      = view.findViewById(R.id.btnGoUrl);
        ExtendedFloatingActionButton fabDownload = view.findViewById(R.id.fabDownload);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView w, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                pageReady = newProgress >= 100;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView w, String url, Bitmap favicon) {
                pageReady = false;
                updateNavButtons();
                if (url != null && !url.startsWith("about:")) {
                    browserUrlInput.setText(url);
                }
            }

            @Override
            public void onPageFinished(WebView w, String url) {
                pageReady = true;
                updateNavButtons();
                CookieManager.getInstance().flush();
                if (url != null && !url.startsWith("about:")) {
                    browserUrlInput.setText(url);
                }
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

        btnPaste.setOnClickListener(v -> pasteFromClipboard(false));
        btnGo.setOnClickListener(v -> navigateFromAddressBar());

        browserUrlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigateFromAddressBar();
                return true;
            }
            return false;
        });

        fabDownload.setOnClickListener(v -> scanPageForVideos(fabDownload));

        String startUrl = getArguments() != null
                ? getArguments().getString(ARG_URL, "https://www.google.com")
                : "https://www.google.com";

        loadUrlInWebView(startUrl);

        return view;
    }

    private void pasteFromClipboard(boolean autoNavigate) {
        if (getActivity() == null) return;
        ClipboardManager cm = (ClipboardManager)
                getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Utils.showAnimatedToast(getActivity(), getString(R.string.browser_paste_empty),
                    R.drawable.alert_error, Utils.ToastDuration.SHORT);
            return;
        }
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return;
        CharSequence text = cd.getItemAt(0).coerceToText(requireContext());
        if (text == null || text.toString().trim().isEmpty()) return;
        String pasted = text.toString().trim();
        browserUrlInput.setText(pasted);
        if (autoNavigate || Utils.isValidUrl(pasted)
                || (pasted.contains(".") && !pasted.contains(" "))) {
            navigateFromAddressBar();
        } else {
            Utils.showAnimatedToast(getActivity(), getString(R.string.browser_paste_done),
                    R.drawable.check_mark, Utils.ToastDuration.SHORT);
        }
    }

    private void navigateFromAddressBar() {
        String input = browserUrlInput.getText().toString().trim();
        if (input.isEmpty()) return;

        String target;
        if (Utils.isValidUrl(input)) {
            target = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            target = input.startsWith("http") ? input : "https://" + input;
        } else {
            Utils.showAnimatedToast(getActivity(), getString(R.string.browser_invalid_url),
                    R.drawable.alert_error, Utils.ToastDuration.SHORT);
            return;
        }
        loadUrlInWebView(target);
    }

    private void loadUrlInWebView(String url) {
        if (webView == null || url == null || url.trim().isEmpty()) return;

        if (UrlResolver.needsExpansion(url)) {
            progressBar.setVisibility(View.VISIBLE);
            new Thread(() -> {
                String resolved = UrlResolver.resolve(url);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    browserUrlInput.setText(resolved);
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
            browserUrlInput.setText(url);
            webView.loadUrl(url);
        } else {
            Utils.showAnimatedToast(getActivity(),
                    getString(R.string.browser_invalid_url),
                    R.drawable.alert_error, Utils.ToastDuration.SHORT);
        }
    }

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

        if (isGoogleSearchPage(currentUrl)) {
            Utils.showAnimatedToast(getActivity(),
                    getString(R.string.browser_use_address_bar),
                    R.drawable.warning, Utils.ToastDuration.LONG);
        }

        if (!pageReady || progressBar.getProgress() < 90) {
            Utils.showAnimatedToast(getActivity(),
                    getString(R.string.browser_scan_wait),
                    R.drawable.warning, Utils.ToastDuration.SHORT);
        }

        fab.setText("Scanning…");
        fab.setEnabled(false);

        // Delay lets lazy-loaded players (TikTok, IG) attach video sources on slower phones.
        mainHandler.postDelayed(() -> runScanPass(fab, currentUrl, 0), 600);
    }

    private void runScanPass(ExtendedFloatingActionButton fab, String pageUrl, int pass) {
        String cookies   = collectPageCookies(pageUrl);
        String userAgent = webView.getSettings().getUserAgentString();

        AtomicInteger pending = new AtomicInteger(3);
        final String[] jsJsonHolder = { "[]" };
        final String[] htmlHolder   = { "" };
        final String[] cookieHolder = { "" };

        Runnable tryCombined = () -> {
            if (pending.decrementAndGet() > 0) return;
            String mergedCookies = mergeCookieStrings(cookies, cookieHolder[0]);
            runCombinedScraper(pageUrl, mergedCookies, userAgent, fab,
                    jsJsonHolder[0], htmlHolder[0], pass);
        };

        webView.evaluateJavascript(JS_EXTRACT_VIDEOS, value -> {
            jsJsonHolder[0] = unwrapJsString(value);
            tryCombined.run();
        });

        webView.evaluateJavascript(JS_GET_HTML, value -> {
            htmlHolder[0] = unwrapJsString(value);
            tryCombined.run();
        });

        webView.evaluateJavascript(JS_GET_DOCUMENT_COOKIE, value -> {
            cookieHolder[0] = unwrapJsString(value);
            tryCombined.run();
        });
    }

    private static String mergeCookieStrings(String cookieManagerCookies, String documentCookies) {
        if (documentCookies == null || documentCookies.trim().isEmpty()) {
            return cookieManagerCookies != null ? cookieManagerCookies : "";
        }
        if (cookieManagerCookies == null || cookieManagerCookies.trim().isEmpty()) {
            return documentCookies.trim();
        }
        return cookieManagerCookies.trim() + "; " + documentCookies.trim();
    }

    /** Merge cookies for page URL and site root (WebView varies by OEM). */
    private String collectPageCookies(String pageUrl) {
        CookieManager cm = CookieManager.getInstance();
        StringBuilder sb = new StringBuilder();
        appendCookies(sb, cm.getCookie(pageUrl));
        try {
            Uri uri = Uri.parse(pageUrl);
            if (uri.getHost() != null) {
                appendCookies(sb, cm.getCookie(uri.getScheme() + "://" + uri.getHost()));
                appendCookies(sb, cm.getCookie(uri.getScheme() + "://" + uri.getHost() + "/"));
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static void appendCookies(StringBuilder sb, String chunk) {
        if (chunk == null || chunk.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append("; ");
        sb.append(chunk.trim());
    }

    private static boolean isGoogleSearchPage(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("google.") && lower.contains("/search");
    }

    private void runCombinedScraper(String pageUrl,
                                    String cookies,
                                    String userAgent,
                                    ExtendedFloatingActionButton fab,
                                    String jsJson,
                                    String html,
                                    int pass) {
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
                        jsJson != null ? jsJson : "[]",
                        userAgent != null ? userAgent : ""
                );

                String json = result != null ? result.toString() : "[]";
                if (getActivity() == null) return;

                getActivity().runOnUiThread(() -> {
                    fab.setText(getString(R.string.browser_download_fab));
                    fab.setEnabled(true);
                    parseAndShowResults(json, cookies, userAgent, pageUrl, fab, pass);
                });

            } catch (Exception e) {
                Log.e(TAG, "Combined scraper failed", e);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (pass < 1) {
                        // Retry once — Python cold start can fail on low-RAM devices.
                        mainHandler.postDelayed(
                                () -> runScanPass(fab, pageUrl, pass + 1), 800);
                        return;
                    }
                    fab.setText(getString(R.string.browser_download_fab));
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
                                     String pageUrl,
                                     ExtendedFloatingActionButton fab,
                                     int pass) {
        List<VideoEntry> entries = new ArrayList<>();
        String errorMessage = null;

        try {
            String trimmed = json.trim();

            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                if (obj.has("error")) {
                    errorMessage = obj.getString("error");
                }
            }

            if (errorMessage == null) {
                JSONArray arr = new JSONArray(trimmed);
                Map<String, VideoEntry> seen = new LinkedHashMap<>();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    if (obj.has("error") && errorMessage == null) {
                        errorMessage = obj.optString("error", "");
                        continue;
                    }
                    String url   = obj.optString("url", "").trim();
                    String title = obj.optString("title", "").trim();
                    String thumb = obj.optString("thumb", "").trim();
                    int    dur   = obj.optInt("duration", 0);

                    if (url.isEmpty() || url.startsWith("blob:")) continue;
                    if (isGoogleSearchPage(url)) continue;

                    String key = url.split("\\?")[0].split("#")[0];
                    if (!seen.containsKey(key)) {
                        if (title.isEmpty()) title = "Video";
                        seen.put(key, new VideoEntry(url, title, thumb, dur));
                    }
                }

                entries.addAll(seen.values());
            }

        } catch (Exception e) {
            Log.e(TAG, "JSON parse error", e);
        }

        if (errorMessage != null && !errorMessage.isEmpty()) {
            Utils.showAnimatedToast(getActivity(), errorMessage,
                    R.drawable.alert_error, Utils.ToastDuration.LONG);
        }

        if (entries.isEmpty()) {
            entries.add(new VideoEntry(pageUrl, "This page", "", 0));
        } else {
            entries = sortVideoEntries(entries, pageUrl);
        }

        // On slow WebViews only the page URL is found — retry once after a longer wait.
        if (pass < 1 && entries.size() <= 1 && !isGoogleSearchPage(pageUrl)) {
            fab.setText("Scanning…");
            fab.setEnabled(false);
            mainHandler.postDelayed(() -> runScanPass(fab, pageUrl, pass + 1), 1500);
            return;
        }

        showPicker(entries, cookies, userAgent, pageUrl);
    }

    private List<VideoEntry> sortVideoEntries(List<VideoEntry> entries, String pageUrl) {
        List<VideoEntry> pageFirst   = new ArrayList<>();
        List<VideoEntry> watchPages  = new ArrayList<>();
        List<VideoEntry> directFiles = new ArrayList<>();

        String pageKey = normalizeUrlKey(pageUrl);

        for (VideoEntry entry : entries) {
            if (pageKey.equals(normalizeUrlKey(entry.url))) {
                pageFirst.add(new VideoEntry(entry.url, "This page", entry.thumb, entry.durationSecs));
            } else if (UrlResolver.isDirectMediaUrl(entry.url)) {
                directFiles.add(entry);
            } else {
                watchPages.add(entry);
            }
        }

        if (pageFirst.isEmpty() && pageUrl != null && !pageUrl.isEmpty()) {
            pageFirst.add(new VideoEntry(pageUrl, "This page", "", 0));
        }

        List<VideoEntry> sorted = new ArrayList<>();
        sorted.addAll(pageFirst);
        sorted.addAll(watchPages);
        sorted.addAll(directFiles);
        return sorted;
    }

    private static String normalizeUrlKey(String url) {
        if (url == null) return "";
        return url.split("#")[0].split("\\?")[0].replaceAll("/$", "").toLowerCase();
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
                    .replace("\\u0026", "&")
                    .replace("\\u002F", "/")
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
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroyView();
    }
}
