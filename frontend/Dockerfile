ARG BACKEND_BASEURL=http://localhost:8080

FROM node:18-alpine AS build

ARG BACKEND_BASEURL
ENV BACKEND_BASEURL=${BACKEND_BASEURL}
ENV VITE_API_URL=${BACKEND_BASEURL}


WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .

RUN npm run build

FROM nginx:alpine AS runtime

ARG BACKEND_BASEURL
ENV BACKEND_BASEURL=${BACKEND_BASEURL}
ENV VITE_API_URL=${BACKEND_BASEURL}

RUN echo $VITE_API_URL
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
