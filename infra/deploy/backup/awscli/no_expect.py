"""AWS CLI plugin: do not send "Expect: 100-continue" on uploads.

botocore adds that header to every upload. Real S3 and Cloudflare R2 cope with it, some S3-compatible servers (the
Adobe S3Mock used in the local rehearsal among them) drop the connection. Sending the body straight away costs nothing.
The header is not part of the signature, so removing it from the prepared request is safe.
"""


def _drop_expect(request, **kwargs):
    try:
        del request.headers["Expect"]
    except KeyError:
        pass


def awscli_initialize(cli):
    cli.register("request-created.s3", _drop_expect)
