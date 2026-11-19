# Artificial-intelligence-browser

متصفح أندرويد (Galaxy S25 Ultra) يرفع دقة الفيديو والصور بالذكاء الاصطناعي على الجهاز نفسه، بدون استهلاك إنترنت إضافي.

## المكوّنات
- `android/app/src/main/java/.../MainActivity.kt`: المتصفح (WebView): حظر إعلانات ومنبثقات، حماية، سجل، أزرار مشغّل.
- `android/app/src/main/java/.../PlayerActivity.kt`: المشغّل الأصلي: فك تشفير بالعتاد (ExoPlayer) + رفع دقة 4× بنموذج Real-ESRGAN x4v3 على المعالج العصبي (ONNX Runtime + Qualcomm QNN)، مع محسّن حوار.
- `android/app/src/main/assets/engine.js`: محرك داخل الصفحة: رفع دقة عصبي (WebGPU) أو تحسين بالـGPU (WebGL2)، إزالة ضوضاء، حدة، تباين HDR تكيفي.
- `tools/`: تثبيت أبعاد النموذج وتكميمه (A16W8) للمعالج العصبي وقت البناء.
- `engine-tests/`: اختبارات المحرك في Chromium (Playwright).
- `tools/ui-harness/`: يشغّل واجهة المتصفح الحقيقية (MainActivity و Ui.kt) على Robolectric بدون جهاز ولا Android SDK، ويصوّرها بالعربي (RTL) والإنجليزي: `cd tools/ui-harness && gradle test` ثم الصور في `build/shots/` (`SEED=1` يضيف سجلاً وأفلاماً تجريبية). يتحقق أيضاً من أن الكود يُترجم.

## البناء
كل دفع إلى `main` يبني التطبيق للتحقق فقط. نسخة التحميل تُنشر يدوياً: Actions ← Build Android APK ← Run workflow.
