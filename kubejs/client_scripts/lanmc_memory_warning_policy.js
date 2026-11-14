// priority: 1000
// KubeJS loads this before Memory Settings decides whether to show the menu panel.
(() => {
  const Configuration = Java.loadClass('com.memorysettings.config.CommonConfiguration')
  Configuration.config.getCommonConfig().disableWarnings = true
})()
