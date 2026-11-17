<?php
require_once __DIR__ . '/config.php';

function cors() {
    header('Access-Control-Allow-Origin: ' . ALLOWED_ORIGIN);
    header('Vary: Origin');
    header('Access-Control-Allow-Headers: Content-Type');
    header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
    header('Content-Type: application/json; charset=utf-8');
    if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') { http_response_code(204); exit; }
}

function out($data, $code = 200) {
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function valid_cid($c) { return is_string($c) && preg_match('/^[a-f0-9]{32}$/', $c); }

function db_path() {
    if (!is_dir(DATA_DIR)) {
        mkdir(DATA_DIR, 0700, true);
        file_put_contents(DATA_DIR . '/.htaccess',
            "<IfModule mod_authz_core.c>\nRequire all denied\n</IfModule>\n<IfModule !mod_authz_core.c>\nDeny from all\n</IfModule>\n");
    }
    return DATA_DIR . '/payments.json';
}

function db_read() {
    $p = db_path();
    return is_file($p) ? (json_decode(file_get_contents($p), true) ?: []) : [];
}

function db_update(callable $fn) {
    $f = fopen(db_path(), 'c+');
    flock($f, LOCK_EX);
    $d = json_decode(stream_get_contents($f), true) ?: [];
    $fn($d);
    ftruncate($f, 0); rewind($f);
    fwrite($f, json_encode($d));
    fflush($f); flock($f, LOCK_UN); fclose($f);
}

function chargily($method, $path, $body = null) {
    $ch = curl_init(api_base() . $path);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT        => 20,
        CURLOPT_CUSTOMREQUEST  => $method,
        CURLOPT_HTTPHEADER     => ['Authorization: Bearer ' . CHARGILY_SECRET, 'Content-Type: application/json'],
    ]);
    if ($body !== null) curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
    $r = curl_exec($ch);
    curl_close($ch);
    return json_decode($r ?: '[]', true) ?: [];
}
