// Stand-in for the Google IMA video-ads SDK: players load it fine, ask for an ad, get "no ad" and play the movie.
(function () {
  if (window.google && window.google.ima && window.google.ima.__aib) return
  function Emitter () { this._l = {} }
  Emitter.prototype.addEventListener = function (t, f) { [].concat(t).forEach(function (k) { (this._l[k] = this._l[k] || []).push(f) }, this) }
  Emitter.prototype.removeEventListener = function (t, f) { [].concat(t).forEach(function (k) { this._l[k] = (this._l[k] || []).filter(function (g) { return g !== f }) }, this) }
  Emitter.prototype._emit = function (t, e) { (this._l[t] || []).slice().forEach(function (f) { try { typeof f === 'function' ? f(e) : f.handleEvent(e) } catch (x) { console.error(x) } }) }
  function AdError (m, c) { this.m = m; this.c = c }
  AdError.prototype.getMessage = function () { return this.m }
  AdError.prototype.getErrorCode = function () { return this.c }
  AdError.prototype.getVastErrorCode = function () { return 303 }
  AdError.prototype.getType = function () { return 'adLoadError' }
  AdError.prototype.getInnerError = function () { return null }
  AdError.prototype.toString = function () { return 'AdError ' + this.c + ': ' + this.m }
  AdError.ErrorCode = { VAST_EMPTY_RESPONSE: 1009, UNKNOWN_ERROR: 900 }
  AdError.Type = { AD_LOAD: 'adLoadError', AD_PLAY: 'adPlayError' }
  function AdErrorEvent (err, ctx) { this.type = 'adError'; this.e = err; this.ctx = ctx }
  AdErrorEvent.prototype.getError = function () { return this.e }
  AdErrorEvent.prototype.getUserRequestContext = function () { return this.ctx || {} }
  AdErrorEvent.Type = { AD_ERROR: 'adError' }
  function AdsManagerLoadedEvent () {}
  AdsManagerLoadedEvent.Type = { ADS_MANAGER_LOADED: 'adsManagerLoaded' }
  var settings = new Proxy({ getLocale: function () { return 'en' }, getDisableCustomPlaybackForIOS10Plus: function () { return false }, getNumRedirects: function () { return 4 }, getPlayerType: function () { return '' }, getPlayerVersion: function () { return '' }, getPpid: function () { return '' }, getFeatureFlags: function () { return {} } }, { get: function (t, k) { return k in t ? t[k] : function () {} } })
  function AdDisplayContainer () {}
  AdDisplayContainer.prototype.initialize = function () {}
  AdDisplayContainer.prototype.destroy = function () {}
  function AdsLoader () { Emitter.call(this) }
  AdsLoader.prototype = Object.create(Emitter.prototype)
  AdsLoader.prototype.getSettings = function () { return settings }
  AdsLoader.prototype.contentComplete = function () {}
  AdsLoader.prototype.destroy = function () {}
  AdsLoader.prototype.getVersion = function () { return '3.0' }
  AdsLoader.prototype.requestAds = function (req, ctx) {
    var self = this
    setTimeout(function () { self._emit('adError', new AdErrorEvent(new AdError('No ads available', 1009), ctx)) }, 10)
  }
  function AdsRequest () {}
  AdsRequest.prototype.setAdWillAutoPlay = AdsRequest.prototype.setAdWillPlayMuted = AdsRequest.prototype.setContinuousPlayback = function () {}
  function AdsRenderingSettings () {}
  function ImaSdkSettings () {}
  ImaSdkSettings.VpaidMode = { DISABLED: 0, ENABLED: 1, INSECURE: 2 }
  ImaSdkSettings.CompanionBackfillMode = { ALWAYS: 'always', ON_MASTER_AD: 'on_master_ad' }
  var AdEvent = function () {}; AdEvent.Type = new Proxy({}, { get: function (t, k) { return String(k).toLowerCase() } })
  var ima = {
    __aib: true, VERSION: '3.0', settings: settings, AdDisplayContainer: AdDisplayContainer, AdsLoader: AdsLoader, AdsRequest: AdsRequest,
    AdsRenderingSettings: AdsRenderingSettings, ImaSdkSettings: ImaSdkSettings, AdError: AdError, AdErrorEvent: AdErrorEvent,
    AdsManagerLoadedEvent: AdsManagerLoadedEvent, AdEvent: AdEvent, ViewMode: { NORMAL: 'normal', FULLSCREEN: 'fullscreen' },
    UiElements: { AD_ATTRIBUTION: 'adAttribution', COUNTDOWN: 'countdown' }, UniversalAdIdInfo: function () {},
    CompanionAdSelectionSettings: function () {}, OmidAccessMode: { LIMITED: 'limited', DOMAIN: 'domain', FULL: 'full' }
  }
  ima.CompanionAdSelectionSettings.CreativeType = { ALL: 'All', FLASH: 'Flash', IMAGE: 'Image' }
  ima.CompanionAdSelectionSettings.ResourceType = { ALL: 'All', HTML: 'Html', IFRAME: 'IFrame', STATIC: 'Static' }
  ima.CompanionAdSelectionSettings.SizeCriteria = { IGNORE: 'IgnoreSize', SELECT_EXACT_MATCH: 'SelectExactMatch', SELECT_NEAR_MATCH: 'SelectNearMatch' }
  window.google = window.google || {}
  window.google.ima = ima
})()
