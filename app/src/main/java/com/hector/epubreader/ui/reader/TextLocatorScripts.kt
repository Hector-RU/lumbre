package com.hector.epubreader.ui.reader

/** Only reader-owned scripts run; publication scripts are removed and blocked by CSP. */
internal object TextLocatorScripts {
    fun capture(horizontal: Boolean) = """
        (function() {
          var horizontal = $horizontal;
          // Chromium may pan a visual viewport inside a wider layout viewport.
          // DOM Range coordinates are relative to the latter, even at scale 1.
          var viewport = window.visualViewport;
          var left = viewport ? viewport.offsetLeft : 0;
          var top = viewport ? viewport.offsetTop : 0;
          var right = left + (viewport ? viewport.width : innerWidth);
          var bottom = top + (viewport ? viewport.height : innerHeight);
          var spans = document.querySelectorAll('[data-reader-offset]');
          for (var s = 0; s < spans.length; s++) {
            var span = spans[s], node = span.firstChild;
            if (!node || node.nodeType !== 3 || !node.length) continue;
            var range = document.createRange(); range.selectNodeContents(node);
            var rects = range.getClientRects(), visible = false;
            for (var r = 0; r < rects.length; r++) {
              var box = rects[r];
              if (box.width > 0 && box.height > 0 && box.right > left && box.left < right && box.bottom > top && box.top < bottom) { visible = true; break; }
            }
            if (!visible) continue;
            var low = 0, high = node.length - 1;
            while (low < high) {
              var mid = Math.floor((low + high) / 2);
              range.setStart(node, mid); range.setEnd(node, mid + 1);
              var box = range.getBoundingClientRect();
              var before = horizontal ? box.right <= left : box.bottom <= top;
              if (before) low = mid + 1; else high = mid;
            }
            return Number(span.getAttribute('data-reader-offset')) + low;
          }
          return -1;
        })()
    """.trimIndent()

    fun restore(offset: Int, horizontal: Boolean) = """
        (function() {
          var target = ${offset.coerceAtLeast(0)};
          var spans = document.querySelectorAll('[data-reader-offset]');
          for (var i = 0; i < spans.length; i++) {
            var span = spans[i], node = span.firstChild;
            if (!node || node.nodeType !== 3 || !node.length) continue;
            var start = Number(span.getAttribute('data-reader-offset'));
            if (target >= start + node.length && i < spans.length - 1) continue;
            var local = Math.max(0, Math.min(node.length - 1, target - start));
            var range = document.createRange(); range.setStart(node, local); range.setEnd(node, local + 1);
            var box = range.getBoundingClientRect();
            if (!box.width && !box.height) continue;
            return Math.max(0, ($horizontal ? box.left + window.scrollX : box.top + window.scrollY) * (window.devicePixelRatio || 1));
          }
          return -1;
        })()
    """.trimIndent()
}
