package com.hector.epubreader.ui.reader

/** Return canonical UTF-16 boundaries without exposing a JavaScript bridge to books. */
internal val annotationSelectionScript = """
(function() {
  var selection = window.getSelection();
  if (!selection || selection.isCollapsed || !selection.rangeCount) return '';
  var range = selection.getRangeAt(0), start = null, end = null;
  var markers = document.querySelectorAll('[data-reader-offset]');
  for (var i = 0; i < markers.length; i++) {
    var node = markers[i].firstChild;
    if (!node || node.nodeType !== 3 || !range.intersectsNode(node)) continue;
    var a = 0, b = node.length;
    if (node === range.startContainer) a = range.startOffset;
    else if (range.comparePoint(node, 0) === -1) {
      while (a < b) { var mid = Math.floor((a + b) / 2); if (range.comparePoint(node, mid) === -1) a = mid + 1; else b = mid; }
    }
    var from = a; a = from; b = node.length;
    if (node === range.endContainer) b = range.endOffset;
    else { while (a < b) { var mid = Math.ceil((a + b) / 2); if (range.comparePoint(node, mid) === 1) b = mid - 1; else a = mid; } b = a; }
    if (b > from) {
      var base = Number(markers[i].getAttribute('data-reader-offset'));
      if (start === null) start = base + from;
      end = base + b;
    }
  }
  return start !== null && end > start && end - start <= 10000 ? start + '|' + end : '';
})()
""".trimIndent()
