ipfs add --cid-version=1 --raw-leaves input.txt

# you can pin the content on pinata if you don't want to keep it on your private node
#curl -X POST \
#     -H "Authorization: Bearer <YOUR_JWT_GOES_HERE>" \
#     -H "Content-Type: application/json" \
#     -d '{
#           "hashToPin": "e.g. bafkreidmnunlkkbvzht472ipxaowthykcgyur7i6e2az2xhherzpb3ufxy"
#         }' \
#     https://api.pinata.cloud/pinning/pinByHash