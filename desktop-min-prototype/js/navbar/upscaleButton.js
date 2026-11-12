const webviews = require('webviews.js')
const settings = require('util/settings/settings.js')
const remoteMenu = require('remoteMenuRenderer.js')

const DEFAULTS = { enabled: false, target: 1440, sharpness: 0.6, images: true, neural: true }
const TARGETS = [[720, '720p (HD)'], [1080, '1080p (Full HD)'], [1440, '1440p (2K)'], [2160, '2160p (4K)']]

function getConfig () {
  return Object.assign({}, DEFAULTS, settings.get('aiUpscale') || {})
}

function pushToTabs () {
  const cfg = getConfig()
  tasks.forEach(function (task) {
    task.tabs.forEach(function (tab) {
      if (tab.url && tab.url.startsWith('http') && webviews.hasViewForTab(tab.id)) {
        try { webviews.callAsync(tab.id, 'send', ['upscaleConfig', cfg]) } catch (e) { /* view may be gone */ }
      }
    })
  })
}

function update (button) {
  button.style.opacity = getConfig().enabled ? 1 : 0.5
}

function save (patch, button) {
  settings.set('aiUpscale', Object.assign(getConfig(), patch))
  update(button)
  pushToTabs()
}

function initialize () {
  const button = document.getElementById('upscale-button')
  update(button)

  // pages ask for their config when they finish loading
  webviews.bindIPC('upscaleGetConfig', function (tabId) {
    webviews.callAsync(tabId, 'send', ['upscaleConfig', getConfig()])
  })

  button.addEventListener('click', function () {
    const cfg = getConfig()
    remoteMenu.open([
      [{
        type: 'checkbox',
        label: 'رفع الدقة بالذكاء الاصطناعي / AI Upscaling',
        checked: cfg.enabled,
        click: () => save({ enabled: !cfg.enabled }, button)
      }],
      TARGETS.map(([h, label]) => ({
        type: 'radio',
        label: label,
        checked: cfg.target === h,
        click: () => save({ target: h }, button)
      })),
      [{
        type: 'checkbox',
        label: 'نموذج عصبي (Real-ESRGAN) / Neural model',
        checked: cfg.neural,
        click: () => save({ neural: !cfg.neural }, button)
      }, {
        type: 'checkbox',
        label: 'الصور / Images',
        checked: cfg.images,
        click: () => save({ images: !cfg.images }, button)
      }],
      [0.3, 0.6, 0.9].map((v, i) => ({
        type: 'radio',
        label: ['Sharpness: low', 'Sharpness: medium', 'Sharpness: high'][i],
        checked: cfg.sharpness === v,
        click: () => save({ sharpness: v }, button)
      }))
    ])
  })
}

module.exports = { initialize }
