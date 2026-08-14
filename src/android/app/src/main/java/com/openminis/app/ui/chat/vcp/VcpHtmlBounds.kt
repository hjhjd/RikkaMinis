package com.openminis.app.ui.chat.vcp

internal data class VcpHtmlBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun parseVcpHtmlBounds(value: String?): VcpHtmlBounds? {
    val clean = value?.trim()?.trim('"')?.replace("\\\"", "\"") ?: return null
    val parts = clean.split(',')
    if (parts.size != 4) return null
    val values = parts.map { it.toDoubleOrNull() ?: return null }
    if (values.any { !it.isFinite() }) return null
    val left = values[0].toInt().coerceIn(-MAX_VCP_HTML_OFFSET_CSS, MAX_VCP_HTML_OFFSET_CSS)
    val top = values[1].toInt().coerceIn(-MAX_VCP_HTML_OFFSET_CSS, MAX_VCP_HTML_OFFSET_CSS)
    val width = values[2].toInt().coerceIn(1, MAX_VCP_HTML_WIDTH_CSS)
    val height = values[3].toInt().coerceIn(1, MAX_VCP_HTML_HEIGHT_CSS)
    return VcpHtmlBounds(left, top, width, height)
}

internal const val MAX_VCP_HTML_OFFSET_CSS = 4_096
internal const val MAX_VCP_HTML_WIDTH_CSS = 8_192
internal const val MAX_VCP_HTML_HEIGHT_CSS = 20_000

/** JS writes continuously-updated #vcp-root bounds into the document title. */
internal val VCP_HTML_BOUNDS_SCRIPT = """
(function(){
 if(window.__vcpBoundsInstalled)return;window.__vcpBoundsInstalled=true;
 function root(){return document.querySelector('#vcp-root')||document.querySelector('[data-vcp-root]')||document.body.firstElementChild||document.body;}
 function report(){var r=root();if(!r)return;var b=r.getBoundingClientRect();document.title='VCPBOUNDS:'+Math.round(b.left)+','+Math.round(b.top)+','+Math.ceil(b.width)+','+Math.ceil(Math.max(b.height,r.scrollHeight||0));}
 window.input=function(text){send(String(text||''),null);};
 function send(text,button){text=String(text||'').trim();if(!text)return;if(button){button.disabled=true;button.style.opacity='.6';if(!button.dataset.vcpDone){button.textContent=(button.textContent||'')+' ✓';button.dataset.vcpDone='1';}}location.href='vcp-action://button?text='+encodeURIComponent(text.slice(0,480));}
 document.addEventListener('click',function(e){var b=e.target.closest&&e.target.closest('button');if(!b)return;e.preventDefault();e.stopPropagation();if(b.disabled)return;var text=b.getAttribute('data-send');if(!text){var oc=b.getAttribute('onclick')||'';var m=oc.match(/input\\(\\s*(['\"])([\\s\\S]*?)\\1\\s*\\)/i);if(m)text=m[2];}send(text||(b.textContent||'').trim(),b);},true);
 var r=root();if(r&&window.ResizeObserver)new ResizeObserver(report).observe(r);
 if(r&&window.MutationObserver)new MutationObserver(report).observe(r,{subtree:true,childList:true,attributes:true});
 document.querySelectorAll('img').forEach(function(i){i.addEventListener('load',report);i.addEventListener('error',report)});
 if(document.fonts&&document.fonts.ready)document.fonts.ready.then(report);
 window.addEventListener('load',report);requestAnimationFrame(report);setTimeout(report,100);setTimeout(report,400);setTimeout(report,1000);
})()
""".trimIndent()
