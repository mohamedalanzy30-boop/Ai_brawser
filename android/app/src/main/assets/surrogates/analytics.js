// Stand-in for Google Analytics / gtag: callbacks still run so pages do not hang on "hitCallback".
(function () {
  function cb (args) { for (var i = 0; i < args.length; i++) { var a = args[i]; if (a && typeof a === 'object') { var f = a.hitCallback || a.event_callback; if (typeof f === 'function') setTimeout(f, 1) } } }
  var ga = function () { cb(arguments) }
  ga.create = function () { return { get: function () {}, set: function () {}, send: function () {} } }
  ga.getByName = function () { return null }; ga.getAll = function () { return [] }; ga.remove = function () {}; ga.loaded = true
  var q = window[window.GoogleAnalyticsObject || 'ga']; window[window.GoogleAnalyticsObject || 'ga'] = ga
  if (q && q.q) q.q.forEach(function (a) { cb(a) })
  window.dataLayer = window.dataLayer || []
  if (typeof window.gtag !== 'function') window.gtag = function () { cb(arguments) }
  var dl = window.dataLayer; dl.push = function () { cb(arguments); return Array.prototype.push.apply(dl, arguments) }
})()
