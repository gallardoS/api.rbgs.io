# Synthetic SNS signing fixture

`sns-test.p12` contains a self-signed RSA certificate and its synthetic private key for unit tests only. Its password is `test-only-password`. It is not an AWS credential or a real SNS signing certificate. Tests serve this certificate through a mocked SDK HTTP client; production uses the official AWS certificate retrieval and verification path. Never configure a production client to trust this fixture.
