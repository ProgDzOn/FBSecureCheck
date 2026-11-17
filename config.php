<?php
// ===== عدّل هذه القيم فقط =====
const CHARGILY_SECRET = 'test_sk_ضع_المفتاح_السري_هنا';   // المفتاح السري من لوحة Chargily (لا تنشره أبداً)
const CHARGILY_MODE   = 'test';                            // 'test' للتجربة، 'live' للدفع الحقيقي
const SITE_URL        = 'https://progdzon.github.io/FBSecureCheck/'; // رابط الموقع (للعودة بعد الدفع)
const ALLOWED_ORIGIN  = 'https://progdzon.github.io';      // النطاق المسموح له باستدعاء الخادم
const AMOUNT_DZD      = 10;                                // سعر التفعيل بالدينار (يُحدَّد هنا في الخادم فقط)
const DATA_DIR        = __DIR__ . '/data';                 // مجلد حفظ سجلات الدفع

function api_base() {
    return CHARGILY_MODE === 'live'
        ? 'https://pay.chargily.net/api/v2'
        : 'https://pay.chargily.net/test/api/v2';
}
