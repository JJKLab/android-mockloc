"""WGS-84 / GCJ-02 / BD-09 conversion. All public functions take (lat, lng)."""

import math

PI = 3.1415926535897932384626
_SEMI_AXIS = 6378245.0
_ECC_SQ = 0.00669342162296594323
_X_PI = PI * 3000.0 / 180.0


def out_of_china(lat: float, lng: float) -> bool:
    return lng < 72.004 or lng > 137.8347 or lat < 0.8293 or lat > 55.8271


def _transform_lat(x: float, y: float) -> float:
    r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * PI) + 20.0 * math.sin(2.0 * x * PI)) * 2.0 / 3.0
    r += (20.0 * math.sin(y * PI) + 40.0 * math.sin(y / 3.0 * PI)) * 2.0 / 3.0
    r += (160.0 * math.sin(y / 12.0 * PI) + 320.0 * math.sin(y * PI / 30.0)) * 2.0 / 3.0
    return r


def _transform_lng(x: float, y: float) -> float:
    r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * PI) + 20.0 * math.sin(2.0 * x * PI)) * 2.0 / 3.0
    r += (20.0 * math.sin(x * PI) + 40.0 * math.sin(x / 3.0 * PI)) * 2.0 / 3.0
    r += (150.0 * math.sin(x / 12.0 * PI) + 300.0 * math.sin(x / 30.0 * PI)) * 2.0 / 3.0
    return r


def wgs84_to_gcj02(lat: float, lng: float) -> tuple[float, float]:
    if out_of_china(lat, lng):
        return lat, lng
    d_lat = _transform_lat(lng - 105.0, lat - 35.0)
    d_lng = _transform_lng(lng - 105.0, lat - 35.0)
    rad_lat = lat / 180.0 * PI
    magic = 1.0 - _ECC_SQ * math.sin(rad_lat) * math.sin(rad_lat)
    sqrt_magic = math.sqrt(magic)
    d_lat = (d_lat * 180.0) / ((_SEMI_AXIS * (1.0 - _ECC_SQ)) / (magic * sqrt_magic) * PI)
    d_lng = (d_lng * 180.0) / (_SEMI_AXIS / sqrt_magic * math.cos(rad_lat) * PI)
    return lat + d_lat, lng + d_lng


def gcj02_to_wgs84(lat: float, lng: float, iterations: int = 5) -> tuple[float, float]:
    """Iterative inverse. Naive subtraction leaves ~1-5 m of residual error."""
    if out_of_china(lat, lng):
        return lat, lng
    w_lat, w_lng = lat, lng
    for _ in range(iterations):
        c_lat, c_lng = wgs84_to_gcj02(w_lat, w_lng)
        w_lat += lat - c_lat
        w_lng += lng - c_lng
    return w_lat, w_lng


def gcj02_to_bd09(lat: float, lng: float) -> tuple[float, float]:
    z = math.sqrt(lng * lng + lat * lat) + 0.00002 * math.sin(lat * _X_PI)
    theta = math.atan2(lat, lng) + 0.000003 * math.cos(lng * _X_PI)
    return z * math.sin(theta) + 0.006, z * math.cos(theta) + 0.0065


def bd09_to_gcj02(lat: float, lng: float) -> tuple[float, float]:
    x, y = lng - 0.0065, lat - 0.006
    z = math.sqrt(x * x + y * y) - 0.00002 * math.sin(y * _X_PI)
    theta = math.atan2(y, x) - 0.000003 * math.cos(x * _X_PI)
    return z * math.sin(theta), z * math.cos(theta)


def wgs84_to_bd09(lat: float, lng: float) -> tuple[float, float]:
    return gcj02_to_bd09(*wgs84_to_gcj02(lat, lng))


def bd09_to_wgs84(lat: float, lng: float) -> tuple[float, float]:
    return gcj02_to_wgs84(*bd09_to_gcj02(lat, lng))


def to_wgs84(lat: float, lng: float, datum: str) -> tuple[float, float]:
    return {
        "wgs84": lambda a, b: (a, b),
        "gcj02": gcj02_to_wgs84,
        "bd09": bd09_to_wgs84,
    }[datum](lat, lng)


def parse_latlng(text: str, datum: str = "wgs84") -> tuple[float, float]:
    """Accept 'lat,lng' (and 'lng,lat' when the first number cannot be a latitude)."""
    parts = [p.strip() for p in text.replace(" ", ",").split(",") if p.strip()]
    if len(parts) != 2:
        raise ValueError(f"expected two numbers, got {text!r}")
    a, b = float(parts[0]), float(parts[1])
    lat, lng = (b, a) if abs(a) > 90.0 else (a, b)
    if not (-90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0):
        raise ValueError(f"out of range: lat={lat} lng={lng}")
    return to_wgs84(lat, lng, datum)
