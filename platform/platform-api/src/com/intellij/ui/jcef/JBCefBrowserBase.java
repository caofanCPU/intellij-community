// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.jcef;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.ui.scale.ScaleContext;
import com.intellij.util.IconUtil;
import com.intellij.util.LazyInitializer;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ui.UIUtil;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandler;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import static com.intellij.ui.scale.ScaleType.OBJ_SCALE;
import static com.intellij.ui.scale.ScaleType.SYS_SCALE;

/**
 * Base class for windowed and offscreen browsers.
 */
public abstract class JBCefBrowserBase implements JBCefDisposable {

  @NotNull protected static final String BLANK_URI = "about:blank";
  @NotNull private static final Icon ERROR_PAGE_ICON = AllIcons.General.ErrorDialog;

  @SuppressWarnings("SpellCheckingInspection")
  protected static final String JBCEFBROWSER_INSTANCE_PROP = "JBCefBrowser.instance";
  @NotNull private final DisposeHelper myDisposeHelper = new DisposeHelper();
  @Nullable private volatile LoadDeferrer myLoadDeferrer;
  @NotNull private volatile String myLastRequestedUrl = "";

  private static final LazyInitializer.NotNullValue<String> ERROR_PAGE_READER =
    new LazyInitializer.NotNullValue<>() {
      @Override
      public @NotNull String initialize() {
        try {
          return new String(FileUtil.loadBytes(Objects.requireNonNull(
            JBCefApp.class.getResourceAsStream("resources/load_error.html"))), StandardCharsets.UTF_8);
        }
        catch (IOException | NullPointerException e) {
          Logger.getInstance(JBCefBrowser.class).error("couldn't find load_error.html", e);
        }
        return "";
      }
    };

  private static final LazyInitializer.NotNullValue<ScaleContext.Cache<String>> BASE64_ERROR_PAGE_ICON =
    new LazyInitializer.NotNullValue<>() {
      @Override
      public @NotNull ScaleContext.Cache<String> initialize() {
        return new ScaleContext.Cache<>((ctx) -> {
          try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage image = IconUtil.toBufferedImage(IconUtil.scale(ERROR_PAGE_ICON, ctx), false);
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
          }
          catch (IOException ex) {
            Logger.getInstance(JBCefBrowser.class).error("couldn't write an error image", ex);
          }
          return "";
        });
      }
    };

  /**
   * According to
   * <a href="https://github.com/chromium/chromium/blob/55f44515cd0b9e7739b434d1c62f4b7e321cd530/third_party/blink/public/web/web_view.h#L191">SetZoomLevel</a>
   * docs, there is a geometric progression that starts with 0.0 and 1.2 common ratio.
   * Following functions provide API familiar to developers:
   *
   * @see #setZoomLevel(double)
   * @see #getZoomLevel()
   */
  private static final double ZOOM_COMMON_RATIO = 1.2;
  private static final double LOG_ZOOM = Math.log(ZOOM_COMMON_RATIO);
  @NotNull protected final JBCefClient myCefClient;
  @NotNull protected final CefBrowser myCefBrowser;
  @Nullable private final CefLifeSpanHandler myLifeSpanHandler;
  @Nullable private final CefLoadHandler myLoadHandler;
  private final ReentrantLock myCookieManagerLock = new ReentrantLock();
  protected volatile boolean myIsCefBrowserCreated;
  @Nullable private volatile JBCefCookieManager myJBCefCookieManager;
  private final boolean myIsDefaultClient;

  JBCefBrowserBase(@NotNull JBCefClient cefClient, @NotNull CefBrowser cefBrowser, boolean isNewBrowserCreated, boolean isDefaultClient) {
    myCefClient = cefClient;
    myCefBrowser = cefBrowser;
    myIsDefaultClient = isDefaultClient;

    if (isNewBrowserCreated) {
      cefClient.addLifeSpanHandler(myLifeSpanHandler = new CefLifeSpanHandlerAdapter() {
        @Override
        public void onAfterCreated(CefBrowser browser) {
          myIsCefBrowserCreated = true;
          LoadDeferrer loader = myLoadDeferrer;
          if (loader != null) {
            loader.load();
            myLoadDeferrer = null;
          }
        }
      }, getCefBrowser());

      cefClient.addLoadHandler(myLoadHandler = new CefLoadHandlerAdapter() {
        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
          // do not show error page if another URL has already been requested to load
          if (myLastRequestedUrl.equals(StringUtil.trimTrailing(failedUrl, '/'))) {
            UIUtil.invokeLaterIfNeeded(() -> loadErrorPage(errorText, failedUrl));
          }
        }
      }, getCefBrowser());
    }
    else {
      myLifeSpanHandler = null;
      myLoadHandler = null;
    }
  }

  /**
   * Loads URL.
   */
  public final void loadURL(@NotNull String url) {
    if (myIsCefBrowserCreated) {
      loadUrlImpl(url);
    }
    else {
      myLoadDeferrer = new LoadDeferrer(null, url);
    }
  }

  /**
   * Loads html content.
   *
   * @param html content to load
   * @param url  a dummy URL that may affect restriction policy applied to the content
   */
  public final void loadHTML(@NotNull String html, @NotNull String url) {
    if (myIsCefBrowserCreated) {
      loadHtmlImpl(html, url);
    }
    else {
      myLoadDeferrer = new LoadDeferrer(html, url);
    }
  }

  /**
   * Loads html content.
   */
  public final void loadHTML(@NotNull String html) {
    loadHTML(html, BLANK_URI);
  }

  @NotNull
  public final CefBrowser getCefBrowser() {
    return myCefBrowser;
  }

  /**
   * @param zoomLevel 1.0 is 100%.
   * @see #ZOOM_COMMON_RATIO
   */
  public final void setZoomLevel(double zoomLevel) {
    myCefBrowser.setZoomLevel(Math.log(zoomLevel) / LOG_ZOOM);
  }

  /**
   * @return 1.0 is 100%
   * @see #ZOOM_COMMON_RATIO
   */
  public final double getZoomLevel() {
    return Math.pow(ZOOM_COMMON_RATIO, myCefBrowser.getZoomLevel());
  }

  @NotNull
  public final JBCefClient getJBCefClient() {
    return myCefClient;
  }

  @NotNull
  public final JBCefCookieManager getJBCefCookieManager() {
    myCookieManagerLock.lock();
    try {
      if (myJBCefCookieManager == null) {
        myJBCefCookieManager = new JBCefCookieManager();
      }
      return Objects.requireNonNull(myJBCefCookieManager);
    }
    finally {
      myCookieManagerLock.unlock();
    }
  }

  @SuppressWarnings("unused")
  public final void setJBCefCookieManager(@NotNull JBCefCookieManager jBCefCookieManager) {
    myCookieManagerLock.lock();
    try {
      myJBCefCookieManager = jBCefCookieManager;
    }
    finally {
      myCookieManagerLock.unlock();
    }
  }

  final boolean isCefBrowserCreated() {
    return myIsCefBrowserCreated;
  }

  @Override
  public void dispose() {
    myDisposeHelper.dispose(() -> {
      if (myLifeSpanHandler != null) getJBCefClient().removeLifeSpanHandler(myLifeSpanHandler, getCefBrowser());
      if (myLoadHandler != null) getJBCefClient().removeLoadHandler(myLoadHandler, getCefBrowser());
      myCefBrowser.stopLoad();
      myCefBrowser.close(true);
      if (myIsDefaultClient) {
        Disposer.dispose(myCefClient);
      }
    });
  }

  @Override
  public final boolean isDisposed() {
    return myDisposeHelper.isDisposed();
  }

  /**
   * Returns {@code JBCefBrowser} instance associated with this {@code CefBrowser}.
   */
  @Nullable
  public static JBCefBrowser getJBCefBrowser(@NotNull CefBrowser browser) {
    Component uiComp = browser.getUIComponent();
    if (uiComp != null) {
      Component parentComp = uiComp.getParent();
      if (parentComp instanceof JComponent) {
        return (JBCefBrowser)((JComponent)parentComp).getClientProperty(JBCEFBROWSER_INSTANCE_PROP);
      }
    }
    return null;
  }

  private void loadHtmlImpl(@NotNull String html, @NotNull String url) {
    loadUrlImpl(JBCefFileSchemeHandlerFactory.registerLoadHTMLRequest(getCefBrowser(), html, url));
  }

  private void loadUrlImpl(@NotNull String url) {
    getCefBrowser().loadURL(myLastRequestedUrl = url);
  }

  private void loadErrorPage(@NotNull String errorText, @NotNull String failedUrl) {
    int fontSize = (int)(EditorColorsManager.getInstance().getGlobalScheme().getEditorFontSize() * 1.1);
    int headerFontSize = fontSize + JBUIScale.scale(3);
    int headerPaddingTop = headerFontSize / 5;
    int lineHeight = headerFontSize * 2;
    int iconPaddingRight = JBUIScale.scale(12);
    Color bgColor = JBColor.background();
    String bgWebColor = String.format("#%02x%02x%02x", bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());
    Color fgColor = JBColor.foreground();
    String fgWebColor = String.format("#%02x%02x%02x", fgColor.getRed(), fgColor.getGreen(), fgColor.getBlue());

    String html = ERROR_PAGE_READER.get();
    html = html.replace("${lineHeight}", String.valueOf(lineHeight));
    html = html.replace("${iconPaddingRight}", String.valueOf(iconPaddingRight));
    html = html.replace("${fontSize}", String.valueOf(fontSize));
    html = html.replace("${headerFontSize}", String.valueOf(headerFontSize));
    html = html.replace("${headerPaddingTop}", String.valueOf(headerPaddingTop));
    html = html.replace("${bgWebColor}", bgWebColor);
    html = html.replace("${fgWebColor}", fgWebColor);
    html = html.replace("${errorText}", errorText);
    html = html.replace("${failedUrl}", failedUrl);

    ScaleContext ctx = ScaleContext.create();
    ctx.update(OBJ_SCALE.of(1.2 * headerFontSize / (float)ERROR_PAGE_ICON.getIconHeight()));
    // Reset sys scale to prevent raster downscaling on passing the image to jcef.
    // Overriding is used to prevent scale change during further intermediate context transformations.
    ctx.overrideScale(SYS_SCALE.of(1.0));

    html = html.replace("${base64Image}", ObjectUtils.notNull(BASE64_ERROR_PAGE_ICON.get().getOrProvide(ctx), ""));

    loadHTML(html);
  }

  private final class LoadDeferrer {
    @Nullable private final String myHtml;
    @NotNull private final String myUrl;

    private LoadDeferrer(@Nullable String html, @NotNull String url) {
      myHtml = html;
      myUrl = url;
    }

    public void load() {
      // JCEF demands async loading.
      SwingUtilities.invokeLater(
        myHtml == null ?
        () -> loadUrlImpl(myUrl) :
        () -> loadHtmlImpl(myHtml, myUrl));
    }
  }
}