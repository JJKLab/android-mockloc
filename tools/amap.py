import json
import urllib.parse
import urllib.request

REST = "https://restapi.amap.com"
_UA = "mockloc/0.1 (+https://github.com/; contact: local tool)"


class AmapError(Exception):
    def __init__(self, message: str, infocode: str = ""):
        super().__init__(message)
        self.infocode = infocode


def _get(path: str, params: dict, timeout: float = 10.0) -> dict:
    url = f"{REST}{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("status") != "1":
        raise AmapError(payload.get("info") or "unknown amap error", payload.get("infocode", ""))
    return payload


def geocode(key: str, address: str, city: str = "") -> list[dict]:
    """Geocoding API. Returns [{name, lat, lng, gcj02, adcode, level}] in GCJ-02."""
    params = {"key": key, "address": address, "output": "JSON"}
    if city:
        params["city"] = city
    payload = _get("/v3/geocode/geo", params)
    out = []
    for item in payload.get("geocodes") or []:
        gcj = _split_location(item.get("location"))
        if gcj is None:
            continue
        out.append(
            {
                "name": item.get("formatted_address") or address,
                "lat": gcj[0],
                "lng": gcj[1],
                "gcj02": True,
                "adcode": item.get("adcode", ""),
                "level": item.get("level", ""),
            }
        )
    return out


def search_text(key: str, keywords: str, city: str = "", page_size: int = 15) -> list[dict]:
    """POI text search. Personal keys get only ~100/day, so geocode() is the default path."""
    params = {"key": key, "keywords": keywords, "offset": str(page_size), "page": "1"}
    if city:
        params["city"] = city
    payload = _get("/v3/place/text", params)
    out = []
    for item in payload.get("pois") or []:
        gcj = _split_location(item.get("location"))
        if gcj is None:
            continue
        out.append(
            {
                "name": item.get("name", keywords),
                "lat": gcj[0],
                "lng": gcj[1],
                "gcj02": True,
                "adcode": item.get("adcode", ""),
                "level": item.get("address") or "",
            }
        )
    return out


def _split_location(value):
    if not value or "," not in value:
        return None
    lng_s, lat_s = value.split(",", 1)
    try:
        return float(lat_s), float(lng_s)
    except ValueError:
        return None
