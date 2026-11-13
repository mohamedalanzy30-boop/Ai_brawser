// Stand-in for FuckAdBlock / BlockAdBlock / SniffAdBlock: always reports "no ad blocker".
(function () {
  function D () { this._n = []; this._d = [] }
  D.prototype.setOption = function () { return this }
  D.prototype.check = function () { var n = this._n; setTimeout(function () { n.forEach(function (f) { try { f() } catch (e) {} }) }, 1); return true }
  D.prototype.emitEvent = function () { return this }
  D.prototype.clearEvent = function () { this._n = []; this._d = [] }
  D.prototype.on = function (detected, fn) { if (!detected) { this._n.push(fn); this.check() } return this }
  D.prototype.onDetected = function () { return this }
  D.prototype.onNotDetected = function (fn) { return this.on(false, fn) }
  ;['FuckAdBlock', 'BlockAdBlock', 'SniffAdBlock'].forEach(function (k) { window[k] = D })
  ;['fuckAdBlock', 'blockAdBlock', 'sniffAdBlock'].forEach(function (k) { window[k] = new D() })
})()
