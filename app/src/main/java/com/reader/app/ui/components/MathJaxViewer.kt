package com.reader.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathJaxViewer(
    markdown: String,
    modifier: Modifier = Modifier,
    textColorHex: String = "#1C1B1F",
    textSizePx: Int = 17
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = WebViewClient()
            }
        },
        update = { webView ->
            val cacheKey = "$markdown|$textColorHex|$textSizePx"
            if (webView.tag != cacheKey) {
                webView.tag = cacheKey
                val encodedBytes = markdown.toByteArray(Charsets.UTF_8)
                val base64Md = Base64.encodeToString(encodedBytes, Base64.NO_WRAP)
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                        <script>
                            window.MathJax = {
                                tex: { 
                                    inlineMath: [['$', '$'], ['\\(', '\\)']],
                                    displayMath: [['$$', '$$'], ['\\[', '\\]']]
                                },
                                svg: { fontCache: 'global' },
                                startup: {
                                    ready: function() {
                                        MathJax.startup.defaultReady();
                                    }
                                }
                            };
                        </script>
                        <script id="MathJax-script" async src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js" onerror="this.onerror=null; var script=document.createElement('script'); script.src='https://cdnjs.cloudflare.com/ajax/libs/mathjax/3.2.2/es5/tex-mml-chtml.min.js'; document.head.appendChild(script);"></script>
                        <style>
                            @import url('https://fonts.googleapis.com/css2?family=Noto+Sans:ital,wght@0,400;0,700;1,400;1,700&family=Noto+Sans+Devanagari:wght@400;700&display=swap');
                            body { 
                                font-family: 'Noto Sans', 'Noto Sans Devanagari', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; 
                                font-size: ${textSizePx}px; 
                                color: ${textColorHex}; 
                                padding: 0; 
                                margin: 0; 
                                background: transparent;
                                word-wrap: break-word;
                                line-height: 1.75;
                            }
                            p {
                                margin-top: 14px;
                                margin-bottom: 14px;
                            }
                            p:first-child { margin-top: 0; }
                            p:last-child { margin-bottom: 0; }
                        </style>
                    </head>
                    <body>
                        <div id="content"></div>
                        <script>
                            function b64DecodeUnicode(str) {
                                return decodeURIComponent(atob(str).split('').map(function(c) {
                                    return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
                                }).join(''));
                            }
                            try {
                                const md = b64DecodeUnicode("$base64Md");
                                
                                // Protect math equations from marked parser eating backslashes
                                const mathBlocks = [];
                                let placeholderCounter = 0;
                                let processedMd = md;

                                // 1. Double dollar display math: $$...$$
                                processedMd = processedMd.replace(/\$\$([\s\S]*?)\$\$/g, function(match) {
                                    const placeholder = 'MATHPLACEHOLDERDP' + (placeholderCounter++);
                                    mathBlocks.push({ placeholder: placeholder, content: match });
                                    return placeholder;
                                });

                                // 2. Single dollar inline math: $...$
                                processedMd = processedMd.replace(/\$([^\$\n]+?)\$/g, function(match) {
                                    const placeholder = 'MATHPLACEHOLDERIP' + (placeholderCounter++);
                                    mathBlocks.push({ placeholder: placeholder, content: match });
                                    return placeholder;
                                });

                                // 3. LaTeX display math: \[...\] or \\[...\\]
                                processedMd = processedMd.replace(/\\\\{1,2}\[([\s\S]*?)\\\\{1,2}\]/g, function(match) {
                                    const placeholder = 'MATHPLACEHOLDERLDP' + (placeholderCounter++);
                                    mathBlocks.push({ placeholder: placeholder, content: match });
                                    return placeholder;
                                });

                                // 4. LaTeX inline math: \(...\) or \\(\\...)
                                processedMd = processedMd.replace(/\\\\{1,2}\(([\s\S]*?)\\\\{1,2}\)/g, function(match) {
                                    const placeholder = 'MATHPLACEHOLDERLIP' + (placeholderCounter++);
                                    mathBlocks.push({ placeholder: placeholder, content: match });
                                    return placeholder;
                                });

                                // Parse with marked.js
                                let parsedHtml = marked.parse(processedMd);

                                // Restore all math equations untouched
                                for (let i = 0; i < mathBlocks.length; i++) {
                                    parsedHtml = parsedHtml.replace(mathBlocks[i].placeholder, mathBlocks[i].content);
                                }

                                // Clean up any lingering unreplaced placeholder strings defensively
                                parsedHtml = parsedHtml.replace(/MATHPLACEHOLDER[A-Z0-9_]+/g, '');

                                document.getElementById('content').innerHTML = parsedHtml;

                                // Explicitly trigger MathJax typesetting if available
                                if (window.MathJax && MathJax.typesetPromise) {
                                    MathJax.typesetPromise();
                                }
                            } catch(e) {
                                document.getElementById('content').innerHTML = "Error parsing content: " + e.message;
                            }
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
            }
        }
    )
}

/**
 * Returns true if the given text contains MathJax/LaTeX mathematical content,
 * indicating that heavier rendering is required. If false, normal lightweight
 * Text views can be used for optimal scrolling and immediate click responsiveness.
 */
fun hasMathEquations(text: String): Boolean {
    if (text.isBlank()) return false

    if (text.contains("$") || text.contains("\\(") || text.contains("\\[") || text.contains("\\)")) return true
    val keywords = listOf(
        "\\frac", "\\sqrt", "\\sum", "\\int", "\\alpha", "\\beta", "\\gamma", "\\theta",
        "\\pi", "\\lambda", "\\begin", "\\end", "\\times", "\\div", "\\pm", "\\mp",
        "\\ge", "\\le", "\\ne", "\\approx", "\\propto", "\\infty", "\\cdot"
    )
    for (kw in keywords) {
        if (text.contains(kw)) return true
    }
    if (text.contains("^") || text.contains("_")) return true
    return false
}
