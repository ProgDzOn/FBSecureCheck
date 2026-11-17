<?php
// ينشئ عملية دفع (10 دج) ويعيد رابط صفحة الدفع الآمنة. السعر يُحدَّد هنا ولا يُقبل من المتصفح.
require_once __DIR__ . '/lib.php';
cors();
if ($_SERVER['REQUEST_METHOD'] !== 'POST') out(['error' => 'method'], 405);

$in  = json_decode(file_get_contents('php://input'), true) ?: [];
$cid = $in['cid'] ?? '';
if (!valid_cid($cid)) out(['error' => 'bad_cid'], 400);
$lang = in_array($in['lang'] ?? 'ar', ['ar', 'en', 'fr'], true) ? $in['lang'] : 'ar';

$db = db_read();
if (!empty($db[$cid]['paid'])) out(['paid' => true]);

$res = chargily('POST', '/checkouts', [
    'amount'         => AMOUNT_DZD,
    'currency'       => 'dzd',
    'payment_method' => 'cib',
    'success_url'    => SITE_URL . '?paid=1',
    'failure_url'    => SITE_URL . '?paid=0',
    'locale'         => $lang,
    'description'    => 'SecureBook Pro',
    'metadata'       => ['cid' => $cid],
]);
if (empty($res['id']) || empty($res['checkout_url'])) out(['error' => 'gateway'], 502);

db_update(function (&$d) use ($cid, $res) {
    $d[$cid] = ['paid' => false, 'checkout_id' => $res['id'], 'created' => time()];
});
out(['checkout_url' => $res['checkout_url']]);
