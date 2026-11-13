// Stand-in for Google Publisher Tag (googletag): every call works, no ads are requested.
(function () {
  var noop = function () {}
  var slot = { getSlotElementId: function () { return '' }, getAdUnitPath: function () { return '' }, getTargeting: function () { return [] }, getTargetingKeys: function () { return [] }, getResponseInformation: function () { return null } }
  var slotP = new Proxy(slot, { get: function (t, k) { return k in t ? t[k] : function () { return slotP } } })
  var listeners = {}
  var pub = {
    addEventListener: function (ev, fn) { (listeners[ev] = listeners[ev] || []).push(fn); return pubP },
    removeEventListener: function () { return true },
    getSlots: function () { return [] }, getTargeting: function () { return [] }, getTargetingKeys: function () { return [] },
    isInitialLoadDisabled: function () { return false }, refresh: noop, clear: function () { return true }
  }
  var pubP = new Proxy(pub, { get: function (t, k) { return k in t ? t[k] : function () { return pubP } } })
  var gt = window.googletag || {}
  var cmd = gt.cmd || []
  gt.apiReady = true; gt.pubadsReady = true
  gt.pubads = function () { return pubP }
  gt.companionAds = gt.content = function () { return pubP }
  gt.defineSlot = gt.defineOutOfPageSlot = function () { return slotP }
  gt.destroySlots = function () { return true }
  gt.display = function (id) {
    var s = (listeners.slotRenderEnded || []); s.forEach(function (f) { try { f({ isEmpty: true, slot: slotP }) } catch (e) {} })
  }
  gt.enableServices = gt.disablePublisherConsole = gt.openConsole = gt.setAdIframeTitle = noop
  gt.sizeMapping = function () { var b = { addSize: function () { return b }, build: function () { return [] } }; return b }
  gt.cmd = { push: function () { for (var i = 0; i < arguments.length; i++) try { arguments[i]() } catch (e) {} return 1 } }
  window.googletag = gt
  for (var i = 0; i < cmd.length; i++) try { cmd[i]() } catch (e) {}
})()
