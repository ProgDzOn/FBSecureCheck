<?php
// يتحقق من حالة الدفع مباشرة من بوابة Chargily (خادم إلى خادم) ثم يفعّل الكود إذا كان مدفوعاً.
require_once __DIR__ . '/lib.php';
cors();

$cid = $_GET['cid'] ?? '';
if (!valid_cid($cid)) out(['error' => 'bad_cid'], 400);

$rec = db_read()[$cid] ?? null;
if (!$rec)              out(['paid' => false]);
if (!empty($rec['paid'])) out(['paid' => true]);

$res = chargily('GET', '/checkouts/' . rawurlencode($rec['checkout_id']));
if (($res['status'] ?? '') === 'paid') {
    db_update(function (&$d) use ($cid) {
        $d[$cid]['paid'] = true;
        $d[$cid]['paid_at'] = time();
    });
    out(['paid' => true]);
}
out(['paid' => false, 'status' => $res['status'] ?? 'unknown']);
