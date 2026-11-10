FROM node:20.19.0 AS build
WORKDIR /build
COPY package.json .npmrc ./
RUN npm install --legacy-peer-deps --no-audit --no-fund
COPY . .
RUN npm run build
FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
