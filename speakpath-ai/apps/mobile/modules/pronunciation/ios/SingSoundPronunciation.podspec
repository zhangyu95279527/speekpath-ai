require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json'))) rescue {}

Pod::Spec.new do |s|
  s.name           = 'SingSoundPronunciation'
  s.version        = '0.1.0'
  s.summary        = 'Expo 桥接模块：阿里云智能语音评测（先声 SingSound）iOS SDK'
  s.description    = '封装 SingSound.framework 的在线发音评测能力（warrantId 授权 + 流式评测）。'
  s.homepage       = 'https://speakpath.ai'
  s.license        = 'MIT'
  s.author         = { 'SpeakPath' => 'dev@speakpath.ai' }
  s.platforms      = { :ios => '15.1' }
  s.source         = { :path => '.' }
  s.source_files   = 'ios/**/*.swift'
  s.vendored_frameworks = 'ios/SingSound.framework'

  # 静态 framework，无需嵌入（embed）；由 ExpoModulesCore 提供模块注册与 JS 桥接。
  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'OTHER_LDFLAGS' => '-ObjC'
  }
end
