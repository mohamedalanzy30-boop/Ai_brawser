# Artificial-intelligence-browser

متصفح برفع دقة الفيديو والصور بالذكاء الاصطناعي، يعمل محلياً على الجهاز (بدون استهلاك إنترنت إضافي).

## الحالة
- `desktop-min-prototype/`: نموذج أولي مبني على [Min](https://github.com/minbrowser/min) (Apache-2.0).
  - نموذج عصبي Real-ESRGAN `realesr-general-x4v3` (BSD-3) عبر ONNX Runtime Web (WebGPU، مع WASM احتياطي).
  - مرشّح WebGL فوري احتياطي، وزر في شريط Min لاختيار الدقة (720p–4K) وتفعيل الصور/النموذج العصبي.
  - لم يُختبر النموذج الحقيقي على GPU بعد (تم اختبار مسار الخدمة بنموذج بديل فقط).
- الخطوة التالية: متصفح أندرويد (WebView) لجهاز S25 Ultra بنفس الكود.

## تشغيل النموذج الأولي
```
cd desktop-min-prototype
npm install
npm run start
```
