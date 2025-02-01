package com.cabolabs.platform.notifications

import grails.transaction.Transactional

import static groovyx.net.http.Method.GET
import groovyx.net.http.HTTPBuilder
import grails.util.Holders
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

@Transactional
class RemoteNotificationsService {

   def getNotifications(String app, String lang, Date from = null)
   {
      def res = []
      def error = false
      def status

      try
      {
         withRest(uri: config.notifications.url) {
            // Temporarily disable SSL verification for development/testing
            // TODO: Properly configure SSL certificates in production
            ignoreSSLIssues()
            
            response.success = { resp, reader ->
               res = reader
            }
            
            response.failure = { resp, reader ->
               log.error("Remote notifications request failed: ${resp.statusLine}")
               error = true
               status = resp.status
            }
         }
      }
      catch (Exception e)
      {
         log.error("Error connecting to notifications service: ${e.message}")
         return [] // Return empty list instead of throwing exception
      }

      if (error)
      {
         log.error("Remote notifications service returned error status: ${status}")
         return [] // Return empty list instead of throwing exception
      }

      return res
   }

   private void ignoreSSLIssues() {
      def context = SSLContext.getInstance("TLS")
      context.init(null, [
         new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { null }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
         }
      ] as TrustManager[], null)
      
      def hostnameVerifier = new HostnameVerifier() {
         public boolean verify(String hostname, SSLSession session) { true }
      }
      
      HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
      HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
   }
}
