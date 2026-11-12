// hands MessagePorts from the main process to the context-isolated page
const { ipcRenderer } = require('electron')

const windowLoaded = new Promise(resolve => {
  window.onload = resolve
})

ipcRenderer.on('upscale-session-create', async (event) => {
  await windowLoaded
  window.postMessage('upscale-session-create', '*', event.ports)
})
