package com.hector.epubreader.ui.reader

internal val paginationMetricsScript = """
(function() {
  var dpr = window.devicePixelRatio || 1;
  var flow = document.getElementById('reader-flow');
  if (!flow && document.querySelector('body > .reader-paragraph')) flow = document.body;
  if (!flow) return '0|0';
  var width = flow.getBoundingClientRect().width;
  var count = Math.max(1, Math.ceil(flow.scrollWidth / width));
  // The last column's overflow can omit its trailing margin. Reserve a complete page
  // on the root without widening the column container or adding another column.
  document.documentElement.style.setProperty('min-width', (count * width) + 'px', 'important');
  return count + '|' + (width * dpr);
})()
""".trimIndent()

internal fun captureReadingAnchorScript(page: Int) = """
(function() {
  // Keep this text reference across controls toggles; discard it only on navigation.
  if (window.readerReadingAnchor) return;
  var flow = document.getElementById('reader-flow') || document.body;
  var bounds = flow.getBoundingClientRect();
  var width = bounds.width;
  var scroll = window.visualViewport ? window.scrollX : -document.documentElement.getBoundingClientRect().left;
  // Chromium may pan the viewport before notifying Android about its new size.
  // Locate text in the reader's recorded column, including offscreen ranges.
  var column = $page;
  var left = column * width - scroll;
  var right = left + width;
  var top = 0;
  var bottom = bounds.height;
  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  var node;
  while (node = walker.nextNode()) {
    if (!node.data.trim()) continue;
    var range = document.createRange();
    range.selectNodeContents(node);
    var rects = range.getClientRects();
    for (var i = 0; i < rects.length; i++) {
      var rect = rects[i];
      if (rect.right <= left || rect.left >= right || rect.bottom <= top || rect.top >= bottom) continue;
      // A text node may span many columns. Find its first character on this page.
      var low = 0, high = node.length - 1;
      while (low < high) {
        var middle = Math.floor((low + high) / 2);
        range.setStart(node, middle); range.setEnd(node, middle + 1);
        var character = range.getBoundingClientRect();
        var characterColumn = Math.floor((character.left + scroll) / width);
        if (characterColumn < column || (characterColumn === column && character.bottom <= top)) low = middle + 1;
        else high = middle;
      }
      window.readerReadingAnchor = {node:node, offset:low};
      return;
    }
  }
})()
""".trimIndent()

internal val readingAnchorPageScript = """
(function() {
  var anchor = window.readerReadingAnchor;
  if (!anchor || !document.body.contains(anchor.node) || !anchor.node.length) return -1;
  var offset = Math.min(anchor.offset, anchor.node.length - 1);
  var range = document.createRange();
  range.setStart(anchor.node, offset); range.setEnd(anchor.node, offset + 1);
  var flow = document.getElementById('reader-flow') || document.body;
  var width = flow.getBoundingClientRect().width;
  var scroll = window.visualViewport ? window.scrollX : -document.documentElement.getBoundingClientRect().left;
  return Math.max(0, Math.floor((range.getBoundingClientRect().left + scroll) / width));
})()
""".trimIndent()
