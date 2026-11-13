// Stand-in for Google AdSense: loads "successfully" and reports every slot as unfilled (what Google does when it has no ad).
(function () {
  var q = window.adsbygoogle
  function fill () {
    var ins = document.querySelectorAll('ins.adsbygoogle:not([data-adsbygoogle-status])')
    for (var i = 0; i < ins.length; i++) { ins[i].setAttribute('data-adsbygoogle-status', 'done'); ins[i].setAttribute('data-ad-status', 'unfilled') }
  }
  var api = { loaded: true, push: function () { setTimeout(fill, 0) } }
  window.adsbygoogle = api
  if (q && q.length) setTimeout(fill, 0)
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fill)
})()
