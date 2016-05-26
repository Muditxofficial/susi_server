/**
 *  AbstractAPIHandler
 *  Copyright 17.05.2016 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package org.loklak.server;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;
import org.loklak.data.DAO;
import org.loklak.http.ClientConnection;
import org.loklak.http.RemoteAccess;
import org.loklak.tools.UTF8;

@SuppressWarnings("serial")
public abstract class AbstractAPIHandler extends HttpServlet implements APIHandler {

    private String[] serverProtocolHostStub = null;

    public AbstractAPIHandler() {
        this.serverProtocolHostStub = null;
    }
    
    public AbstractAPIHandler(String[] serverProtocolHostStub) {
        this.serverProtocolHostStub = serverProtocolHostStub;
    }

    @Override
    public String[] getServerProtocolHostStub() {
        return this.serverProtocolHostStub;
    }

    @Override
    public abstract APIServiceLevel getDefaultServiceLevel();

    @Override
    public abstract APIServiceLevel getCustomServiceLevel(Authorization auth);

    @Override
    public JSONObject[] service(Query call, Authorization rights) throws APIException {

        // make call to the embedded api
        if (this.serverProtocolHostStub == null) return new JSONObject[]{serviceImpl(call, rights)};
        
        // make call(s) to a remote api(s)
        JSONObject[] results = new JSONObject[this.serverProtocolHostStub.length];
        for (int rc = 0; rc < results.length; rc++) {
            try {
                StringBuilder urlquery = new StringBuilder();
                for (String key: call.getKeys()) {
                    urlquery.append(urlquery.length() == 0 ? '?' : '&').append(key).append('=').append(call.get(key, ""));
                }
                String urlstring = this.serverProtocolHostStub[rc] + this.getAPIPath() + urlquery.toString();
                byte[] jsonb = ClientConnection.download(urlstring);
                if (jsonb == null || jsonb.length == 0) throw new IOException("empty content from " + urlstring);
                String jsons = UTF8.String(jsonb);
                JSONObject json = new JSONObject(jsons);
                if (json == null || json.length() == 0) {
                    results[rc] = null;
                    continue;
                };
                results[rc] = json;
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return results;
    }
    
    public abstract JSONObject serviceImpl(Query call, Authorization rights) throws APIException;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Query post = RemoteAccess.evaluate(request);
        process(request, response, post);
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Query query = RemoteAccess.evaluate(request);
        query.initPOST(RemoteAccess.getPostMap(request));
        process(request, response, query);
    }
    
    private void process(HttpServletRequest request, HttpServletResponse response, Query query) throws ServletException, IOException {
        
        // basic protection
        APIServiceLevel serviceLevel = getDefaultServiceLevel();
        if (query.isDoS_blackout()) {response.sendError(503, "your request frequency is too high"); return;} // DoS protection
        if (serviceLevel == APIServiceLevel.ADMIN && !query.isLocalhostAccess()) {response.sendError(503, "access only allowed from localhost, your request comes from " + query.getClientHost()); return;} // danger! do not remove this!
         
        // user authentication
        String host = request.getRemoteHost();
        String anon_id = "host:" + host;
        
        JSONObject authentication_obj = DAO.authentication.has(anon_id) ? DAO.authentication.getJSONObject(anon_id) : DAO.authentication.put(anon_id, new JSONObject()).getJSONObject(anon_id);
        String user_id = authentication_obj.has("id") ? authentication_obj.getString("id") : authentication_obj.put("id", "anon@" + host).getString("id");
        
        JSONObject authorization_obj = DAO.authorization.has(user_id) ? DAO.authorization.getJSONObject(user_id) : DAO.authorization.put(user_id, new JSONObject()).getJSONObject(user_id);

        //JSONObject accounting_persistent_obj = DAO.accounting_persistent.has(user_id) ? DAO.accounting_persistent.getJSONObject(anon_id) : DAO.accounting_persistent.put(user_id, new JSONObject()).getJSONObject(user_id);
        Accounting accounting_temporary = DAO.accounting_temporary.get(user_id);
        if (accounting_temporary == null) {accounting_temporary = new Accounting(); DAO.accounting_temporary.put(user_id, accounting_temporary);}
        
        // extract standard query attributes
        String callback = query.get("callback", "");
        boolean jsonp = callback.length() > 0;
        boolean minified = query.get("minified", false);
        
        Authorization rights = new Authorization(authorization_obj);
        rights.setAccounting(accounting_temporary);
        
        try {
            JSONObject json = serviceImpl(query, rights);
            if  (json == null) {
                response.sendError(400, "your request does not contain the required data");
                return;
             }
    
            // write json
            query.setResponse(response, "application/javascript");
            response.setCharacterEncoding("UTF-8");
            PrintWriter sos = response.getWriter();
            if (jsonp) sos.print(callback + "(");
            sos.print(json.toString(minified ? 0 : 2));
            if (jsonp) sos.println(");");
            sos.println();
            query.finalize();
        } catch (APIException e) {
            response.sendError(e.getStatusCode(), e.getMessage());
            return;
        }
    }
}
