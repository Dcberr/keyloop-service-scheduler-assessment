#!/usr/bin/env python3
"""Exercise the seeded scheduler through HTTP; never automatically retry a POST."""
import argparse
from datetime import datetime, timedelta
import json
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Demo:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.samples = []
        self.http = build_opener(NoRedirect())

    def call(self, method, path, expected, body=None, query=None):
        url = self.base + path + ("?" + urlencode(query) if query else "")
        request_id = "demo-" + str(uuid4())
        headers = {"Accept": "application/json", "X-Request-ID": request_id}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = Request(url, data=data, headers=headers, method=method)
        try:
            response = self.http.open(request, timeout=15)
        except HTTPError as error:
            response = error
        except URLError as error:
            raise RuntimeError("HTTP request failed; do not retry an uncertain POST automatically. Check appointment history.") from error
        with response:
            status = response.status
            received = {k.lower(): v for k, v in response.headers.items()}
            result = json.loads(response.read())
        self.samples.append({"method": method, "path": urlsplit(url).path, "status": status,
                             "headers": received, "body": result})
        if status != expected:
            raise RuntimeError(f"{method} {path}: expected {expected}, received {status}: {result}")
        if received.get("x-request-id") != request_id:
            raise RuntimeError("Response request ID did not match the supplied valid ID")
        print(f"{method} {path}: {status}" + (f" {result['code']}" if "code" in result else ""))
        return result, received

    def run(self):
        customers, _ = self.call("GET", "/customers", 200, query={"size": 100})
        selected = None
        for customer in customers["items"]:
            owner = str(UUID(customer["id"]))
            vehicles, _ = self.call("GET", f"/customers/{owner}/vehicles", 200, query={"size": 100})
            if len(vehicles["items"]) >= 2:
                selected = owner, [str(UUID(v["id"])) for v in vehicles["items"][:2]]
                break
        if selected is None:
            raise RuntimeError("Run the demo seed first: a customer with two vehicles is required")
        owner, vehicles = selected
        dealers, _ = self.call("GET", "/dealerships", 200, query={"size": 100})
        selected = None
        for dealer in dealers["items"]:
            dealer_id = str(UUID(dealer["id"]))
            services, _ = self.call("GET", f"/dealerships/{dealer_id}/services", 200, query={"size": 100})
            oil = next((s for s in services["items"] if s["name"] == "Oil Change"), None)
            if oil:
                selected = dealer, oil
                break
        if selected is None:
            raise RuntimeError("The standard demo seed with an Oil Change service is required")
        dealer, service = selected
        # The seed has exactly one qualified oil-change technician at each dealership.
        # Both clients observe availability before the first claims that technician.
        local_now = datetime.now(ZoneInfo(dealer["timeZone"]))
        requests = None
        for day in range(1, 30):
            for hour in (8, 9, 10, 11, 13, 14, 15, 16):
                start = (local_now + timedelta(days=day)).replace(hour=hour, minute=0, second=0, microsecond=0)
                candidates = [{"customerId": owner, "vehicleId": vehicle,
                               "dealershipId": str(UUID(dealer["id"])), "serviceTypeId": str(UUID(service["id"])),
                               "startsAt": start.isoformat(timespec="seconds")} for vehicle in vehicles]
                results = [self.call("GET", "/availability", 200, query=r)[0] for r in candidates]
                if all(r["available"] for r in results):
                    requests = candidates
                    break
            if requests:
                break
        if requests is None:
            raise RuntimeError("No shared future availability found; extend the demo seed calendar or inspect existing bookings")
        print("Both clients observed availability at " + requests[0]["startsAt"])
        appointment, headers = self.call("POST", "/appointments", 201, body=requests[0])
        appointment_id = str(UUID(appointment["id"]))
        expected_path = urlsplit(self.base).path.rstrip("/") + "/appointments/" + appointment_id
        if urlsplit(headers.get("location", "")).path != expected_path:
            raise RuntimeError("Location does not identify the created appointment")
        saved, _ = self.call("GET", "/appointments/" + appointment_id, 200)
        if saved != appointment:
            raise RuntimeError("Retrieved appointment differs from the committed POST result")
        conflict, _ = self.call("POST", "/appointments", 409, body=requests[1])
        if conflict["code"] not in ("NO_AVAILABLE_BAY", "NO_AVAILABLE_TECHNICIAN", "BOOKING_CONFLICT"):
            raise RuntimeError("Expected a resource-capacity conflict for the second vehicle")
        history, _ = self.call("GET", "/appointments", 200,
                               query={"customerId": owner, "vehicleId": vehicles[0], "size": 100})
        # Future ordering is ascending; scan history if previous runs filled its first page.
        page = 0
        while not any(row["id"] == appointment_id for row in history["items"]):
            page += 1
            if page * 100 >= history["totalElements"]:
                raise RuntimeError("Committed appointment missing from customer/vehicle history")
            history, _ = self.call("GET", "/appointments", 200,
                                   query={"customerId": owner, "vehicleId": vehicles[0], "size": 100, "page": page})
        print("History recovered committed appointment " + appointment_id)
        print("Demo passed. One new appointment remains persisted; reruns find another available slot.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080/api/v1")
    parser.add_argument("--output", type=Path, help="Optional file for actual HTTP response samples")
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    if url.scheme not in ("http", "https") or not url.netloc or url.query or url.fragment:
        parser.error("base-url must be an HTTP(S) URL without query or fragment")
    demo = Demo(args.base_url)
    try:
        demo.run()
    finally:
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(demo.samples, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError) as failure:
        print("Demo failed: " + str(failure), file=sys.stderr)
        sys.exit(1)
