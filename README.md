# Artificial-intelligence-browser

متصفح برفع دقة الفيديو والصور بالذكاء الاصطناعي، يعمل محلياً على الجهاز (بدون استهلاك إنترنت إضافي).

## الحالة
- `desktop-min-prototype/`: نموذج أولي مبني على [Min](https://github.com/minbrowser/min) (Apache-2.0).
  - نموذج عصبي Real-ESRGAN `realesr-general-x4v3` (BSD-3) عبر ONNX Runtime Web (WebGPU، مع WASM احتياطي).
  - مرشّح WebGL فوري احتياطي، وزر في شريط Min لاختيار الدقة (720p–4K) وتفعيل الصور/النموذج العصبي.
  - لم يُختبر النموذج الحقيقي على GPU بعد (تم اختبار مسار الخدمة بنموذج بديل فقط).
- `android/`: متصفح أندرويد (WebView) لجهاز S25 Ultra، يحقن `engine.js` في كل صفحة:
  - رفع دقة عصبي (Real-ESRGAN x4v3 عبر ONNX Runtime Web، WebGPU ثم WASM) مع بديل فوري بـ WebGL.
  - إزالة ضوضاء وتكسّر الضغط (مرشّح حافّي)، استقرار زمني، تباين HDR تكيفي (خوارزمية لا شبكة عصبية).
  - توليد إطارات بتقدير حركة (تجريبي، ثقيل، معطّل افتراضياً).
  - يُبنى APK تلقائياً من تبويب Actions (Build Android APK) ويُحمّل النموذج وORT وقت البناء.
- `engine-tests/run.js`: اختبار دخاني للمحرك في Chromium (تم النجاح بنموذج بديل).

## تشغيل النموذج الأولي
```
cd desktop-min-prototype
npm install
npm run start
```
