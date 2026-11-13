// Hides cookie-consent banners (CMPs) and gives the page its scrolling back. Nothing is accepted on your behalf.
(function () {
  if (window.__aibCookies) return; window.__aibCookies = 1
  var SEL = ['#onetrust-banner-sdk', '#onetrust-consent-sdk', '.onetrust-pc-dark-filter', '#CybotCookiebotDialog', '#CybotCookiebotDialogBodyUnderlay',
    '.qc-cmp2-container', '#qc-cmp2-container', '.fc-consent-root', '#didomi-host', '#didomi-notice', '.didomi-popup-open .didomi-popup-backdrop',
    '#sp_message_container', '[id^="sp_message_container"]', '.cc-window', '.cc-banner', '#cookie-law-info-bar', '.cky-consent-container', '.cky-overlay',
    '#usercentrics-root', '#truste-consent-track', '.truste_box_overlay', '#cmpbox', '#cmpwrapper', '.cmp-root', '#iubenda-cs-banner', '.iubenda-cs-container',
    '#termly-code-snippet-support', '.osano-cm-window', '#axeptio_overlay', '#tarteaucitronRoot', '#cookiescript_injected', '#gdpr-cookie-message',
    '.cookie-banner', '.cookie-consent', '.cookie-notice', '#cookie-banner', '#cookie-consent', '#cookie-notice', '#cookieConsent', '.cookieConsent',
    'div[aria-label="cookieconsent"]', 'div[class*="CookieConsent"]', 'div[id*="cookie-consent"]', 'div[class*="cookie-consent"]']
  var css = SEL.join(',') + '{display:none!important;visibility:hidden!important}'
  function addCss () {
    var r = document.head || document.documentElement; if (!r) return setTimeout(addCss, 30)
    var s = document.createElement('style'); s.textContent = css; r.appendChild(s)
  }
  addCss()
  // banners lock scrolling with classes/inline styles on <html>/<body>
  var LOCK = /(cookie|consent|didomi|onetrust|cmp|sp-message|qc-cmp)/i
  function unlock () {
    [document.documentElement, document.body].forEach(function (el) {
      if (!el) return
      for (var i = el.classList.length - 1; i >= 0; i--) if (LOCK.test(el.classList[i])) el.classList.remove(el.classList[i])
      if (el.style.overflow === 'hidden' && document.querySelector(SEL.join(','))) el.style.overflow = ''
    })
  }
  var n = 0, t = setInterval(function () { unlock(); if (++n > 20) clearInterval(t) }, 500)
})()
