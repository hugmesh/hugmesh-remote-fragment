# hugmesh-remote-fragment — static nginx server image.
#
# Models the hugmesh.systems host web container's run stage, but WITHOUT the
# Angular build: this server has NO baked content. Its document root is a shared
# emptyDir the sync sidecar populates at runtime (the SYNC-AND-SERVE-STATIC
# decision), so the image is just stock nginx-unprivileged + our conf.
#
# nginxinc/nginx-unprivileged: listens on 8080, runs as non-root uid 101 — no
# securityContext fight in k8s.
FROM nginxinc/nginx-unprivileged

# Our server block (listen 8080 + gzip + content-addressed immutable cache).
COPY nginx.conf /etc/nginx/conf.d/default.conf

# The content root is the shared emptyDir at /usr/share/nginx/html (mounted by the
# pod, written by the sync sidecar). Nothing is baked in.

EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
